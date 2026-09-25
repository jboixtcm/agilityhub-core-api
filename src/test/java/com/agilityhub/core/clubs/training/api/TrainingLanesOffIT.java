package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.clubs.catalogs.application.CatalogService;
import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import com.agilityhub.core.clubs.training.application.TrainingActor;
import com.agilityhub.core.clubs.training.application.TrainingBookingService;
import com.agilityhub.core.clubs.training.application.TrainingSlotLocks;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.shared.application.LocalLanes;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.support.ConcurrencySupport;
import com.agilityhub.core.support.ConcurrencySupport.HeldTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E5-T07 · S09 R-09-06/R-09-13 on the Mongo path alone: the local lanes are off, so concurrent bookings meet only the
 * partial unique index `training_active_seat`, the `trainingSeq` `$inc`s (the dog's always) and the ring-slot sequences
 * shared with the S06 ring blocks and the S05 ring changes (T-09-28/32/33, R-09-13).
 */
@TestPropertySource(properties = "core.concurrency.local-lanes=false")
class TrainingLanesOffIT extends TrainingFixtures {
    static final String CONTEXT = "training", CATALOGS = "catalogs", SCHEDULING = "scheduling";
    @Autowired LocalLanes lanes; @Autowired TransactionRetries retries; @Autowired TrainingBookingService service; @Autowired TrainingSlotLocks slotLocks;
    @Autowired CatalogService catalogs;
    record Reply(int status, JsonNode body) { String code() { return body.path("code").asText(); } String outcome() { return status + (status >= 400 ? " " + code() : ""); } }

    Reply send(String path, Object body, RequestPostProcessor auth) throws Exception {
        var request = post("/api/v1" + path).header("Host", HOST).with(auth).contentType("application/json").content(mapper.writeValueAsBytes(body))
                .header("Idempotency-Key", UUID.randomUUID().toString());
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsString()));
    }
    Reply booking(String account, String dogId, String localStart, String ringId) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("dogId", dogId); body.put("startsAt", local(localStart).toString()); if (ringId != null) { body.put("ringId", ringId); }
        return send("/training-bookings", body, as(account));
    }
    /** S05 `PATCH /rings/{id}` by the admin, with the ring's current version. */
    Reply ringChange(String ringId, Map<String, Object> change) throws Exception {
        var body = new LinkedHashMap<String, Object>(change);
        body.put("version", ((Number) mongo.findById(ringId, Document.class, "rings").get("version")).longValue());
        var response = mvc.perform(patch("/api/v1/rings/" + ringId).header("Host", HOST).with(as("admin")).contentType("application/json")
                .content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsString()));
    }
    Reply ringBlock(String ringId, String localFrom, String localTo) throws Exception {
        return send("/ring-blocks", Map.of("ringId", ringId, "from", local(localFrom).toString(), "to", local(localTo).toString(), "kind", "BLOCK", "reason", "MAINTENANCE"), as("admin"));
    }
    static Map<String, Long> tally(List<Reply> replies) {
        var result = new TreeMap<String, Long>(); replies.forEach(r -> result.merge(r.outcome(), 1L, Long::sum)); return result;
    }
    /** A committed-later booking by {@code memberId}, through the real service inside the held transaction. */
    Runnable serviceBooking(String account, String memberId, String dogId, String localStart, String ringId) {
        var actor = new TrainingActor("s09-" + account, memberId, "Example " + account, null, TrainingOrigin.APP, TrainingCancelledBy.MEMBER);
        return () -> service.book(actor, dogId, local(localStart), ringId, null, null);
    }
    /** An ACTIVE row written straight into `training_bookings` (no sequence touched): only the unique index can meet it. */
    TrainingBooking row(String dogId, String memberId, String localStart, String ringId, int seat) {
        var starts = local(localStart); var now = clock.instant();
        return new TrainingBooking(UUID.randomUUID().toString(), CLUB, memberId, dogId, ringId, starts, starts.plus(Duration.ofMinutes(30)), ringId + "_" + starts, seat,
                local("2026-10-04T20:00"), TrainingBookingState.ACTIVE, TrainingOrigin.APP, "s09-" + memberId, null, null, null, null, null, null, null, null, now, now, null);
    }
    <T> T whileHeld(HeldTransaction held, Callable<T> request) throws Exception { return whileHeld(held, CONTEXT, request); }
    /** Runs {@code request}, commits {@code held} once {@code context} has retried (the request met the held write), returns the answer. */
    <T> T whileHeld(HeldTransaction held, String context, Callable<T> request) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            double base = retries.retries(context);
            var future = pool.submit(request);
            ConcurrencySupport.awaitRetry(retries, context, base);
            held.commit();
            return future.get();
        } finally { pool.shutdownNow(); }
    }
    long active(Criteria criteria) { return count("training_bookings", Criteria.where("state").is("ACTIVE").andOperator(criteria)); }

    @Test void T_09_32_twoDirectActiveInsertsOnOneSeatHitThePartialUniqueIndex() {
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            mongo.insert(row("s09-d-c0", "s09-m-c0", "2026-10-05T10:00", MUN, 0));
            assertThatThrownBy(() -> mongo.insert(row("s09-d-c1", "s09-m-c1", "2026-10-05T10:00", MUN, 0))).isInstanceOf(DuplicateKeyException.class);
            // The index is partial: a CANCELLED row on the same seat does not count.
            var cancelled = row("s09-d-c2", "s09-m-c2", "2026-10-05T10:00", MUN, 0);
            mongo.insert(new TrainingBooking(cancelled.id(), cancelled.clubId(), cancelled.memberId(), cancelled.dogId(), cancelled.ringId(), cancelled.startsAt(), cancelled.endsAt(),
                    cancelled.slotId(), 0, cancelled.weekStart(), TrainingBookingState.CANCELLED, cancelled.origin(), null, null, null, null, null, null, null, null, null, null, null, null));
        }
        assertThat(active(Criteria.where("ringId").is(MUN))).isEqualTo(1);
    }

    @Test void T_09_32_aBookingThatMeetsAnUncommittedRowOnItsSeatIsRetriedAndAnswersSlotTaken() throws Exception {
        double before = retries.retries(CONTEXT);
        Reply reply;
        try (var held = new HeldTransaction(tx, CLUB, () -> mongo.insert(row("s09-d-c1", "s09-m-c1", "2026-10-05T10:00", MUN, 0)))) {
            reply = whileHeld(held, () -> booking("c0", "s09-d-c0", "2026-10-05T10:00", MUN));
        }
        System.out.println("E5-T07 T-09-32 seat index met by the service: " + reply.outcome() + " · retries by cause: write_conflict "
                + retries.retries(CONTEXT, "write_conflict") + ", duplicate_key " + retries.retries(CONTEXT, "duplicate_key") + " (cumulative)");
        assertThat(reply.outcome()).isEqualTo("409 SLOT_TAKEN");
        assertThat(reply.body().at("/details/reason").asText()).isEqualTo("TRAINING");
        assertThat(retries.retries(CONTEXT)).isGreaterThan(before);
        assertThat(active(Criteria.where("ringId").is(MUN))).isEqualTo(1);
    }

    @Test void T_09_32_lanesOffTwentyDogsOnOneSlotNeverShareASeat() throws Exception {
        assertThat(lanes.enabled()).isFalse();
        for (int capacity : List.of(1, 2)) {
            String ring = capacity == 1 ? MUN : CEN, start = capacity == 1 ? "2026-10-05T10:00" : "2026-10-05T11:00";
            if (capacity == 2) { mongo.updateFirst(Query.query(Criteria.where("_id").is(CEN)), new Update().set("trainingCapacity", 2), "rings"); }
            double retried = retries.retries(CONTEXT), exhausted = retries.exhaustions(CONTEXT);
            var replies = ConcurrencySupport.parallel(20, i -> () -> booking("c" + i, "s09-d-c" + i, start, ring));
            var counts = tally(replies);
            double retriedNow = retries.retries(CONTEXT) - retried, exhaustedNow = retries.exhaustions(CONTEXT) - exhausted;
            System.out.println("E5-T07 T-09-32 lanes off, capacity " + capacity + ", 20 dogs on one slot: " + counts + " · retries " + retriedNow + " · exhausted " + exhaustedNow);
            assertThat(counts.keySet()).isSubsetOf("201", "409 SLOT_TAKEN", "409 STALE_VERSION");
            assertThat(counts).containsEntry("201", (long) capacity);
            assertThat(retriedNow).as("the ring-slot / seat conflicts were met and retried").isPositive();
            assertThat((long) exhaustedNow).isEqualTo(counts.getOrDefault("409 STALE_VERSION", 0L));
            var seats = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("ringId").is(ring).and("state").is("ACTIVE")), Document.class, "training_bookings")
                    .stream().map(d -> d.getInteger("seatIndex")).toList();
            assertThat(seats).hasSize(capacity).doesNotHaveDuplicates();
        }
    }

    @Test void T_09_33_aBookingThatMeetsAHeldTrainingSeqIncIsRetriedAndThenReachesTheLimit() throws Exception {
        book(as("maria"), "s09-d-rock", "2026-10-05T09:00", MUN, 201);
        book(as("maria"), "s09-d-rock", "2026-10-05T09:30", MUN, 201);
        long seq = ((Number) mongo.findById("s09-d-rock", Document.class, "dogs").get("trainingSeq")).longValue();
        Reply reply;
        try (var held = new HeldTransaction(tx, CLUB, serviceBooking("maria", "s09-m-maria", "s09-d-rock", "2026-10-06T09:00", CEN))) {
            reply = whileHeld(held, () -> booking("maria", "s09-d-rock", "2026-10-07T09:00", CAR));
        }
        assertThat(reply.outcome()).isEqualTo("409 TRAINING_LIMIT_REACHED");
        assertThat(reply.body().at("/details/used").asInt()).isEqualTo(3);
        assertThat(retries.retries(CONTEXT, "write_conflict")).isPositive();
        assertThat(((Number) mongo.findById("s09-d-rock", Document.class, "dogs").get("trainingSeq")).longValue() - seq)
                .as("the held booking's $inc; the refused one rolled back").isEqualTo(1);
        assertThat(active(Criteria.where("dogId").is("s09-d-rock"))).isEqualTo(3);
    }

    @Test void T_09_33_lanesOffFiveParallelBookingsOfADogAtTwoOfThreeNeverPassTheLimit() throws Exception {
        book(as("maria"), "s09-d-rock", "2026-10-05T09:00", MUN, 201);
        book(as("maria"), "s09-d-rock", "2026-10-05T09:30", MUN, 201);
        var starts = List.of("2026-10-06T09:00", "2026-10-06T11:00", "2026-10-07T09:00", "2026-10-07T11:00", "2026-10-08T09:00");
        double retried = retries.retries(CONTEXT);
        var replies = ConcurrencySupport.parallel(5, i -> () -> booking("maria", "s09-d-rock", starts.get(i), CEN));
        var counts = tally(replies);
        System.out.println("E5-T07 T-09-33 lanes off, Rock at 2/3, five parallel slots: " + counts + " · retries " + (retries.retries(CONTEXT) - retried));
        assertThat(counts.keySet()).isSubsetOf("201", "409 TRAINING_LIMIT_REACHED", "409 STALE_VERSION");
        assertThat(counts).containsEntry("201", 1L);
        assertThat(active(Criteria.where("dogId").is("s09-d-rock"))).as("never past training.maxPerWeek").isEqualTo(3);
    }

    @Test void T_09_33_underMemberUnitTwoFamilyMembersCannotBookTheSharedDogOnTwoRingsAtOnce() throws Exception {
        parameter("bookings.limitUnit", "MEMBER");
        Reply reply;
        try (var held = new HeldTransaction(tx, CLUB, serviceBooking("joan", "s09-m-joan", "s09-d-rock", "2026-10-06T10:00", MUN))) {
            reply = whileHeld(held, () -> booking("maria", "s09-d-rock", "2026-10-06T10:00", CEN));
        }
        assertThat(reply.outcome()).as("the dog's trainingSeq conflict, retried, sees Joan's booking").isEqualTo("422 DOG_ALREADY_BOOKED");
        // Two rounds: with the held booking, each member stays under training.maxPerWeek (3) whatever wins.
        for (int round = 0; round < 2; round++) {
            String start = "2026-10-0" + (7 + round) + "T10:00";
            var replies = ConcurrencySupport.parallel(2, i -> () -> i == 0 ? booking("joan", "s09-d-rock", start, MUN) : booking("maria", "s09-d-rock", start, CEN));
            System.out.println("E5-T07 step 3 lanes off, MEMBER unit, round " + round + " Joan (MUN) vs Maria (CEN) with Rock: " + replies.stream().map(Reply::outcome).toList());
            assertThat(replies).allSatisfy(r -> assertThat(r.outcome()).isIn("201", "422 DOG_ALREADY_BOOKED", "409 STALE_VERSION"));
            assertThat(active(Criteria.where("dogId").is("s09-d-rock").and("startsAt").is(Date.from(local(start))))).as("one dog, one ring at a time").isLessThanOrEqualTo(1);
        }
    }

    @Test void R_09_13_aRingBlockDuringAnUncommittedBookingConflictsInsteadOfSkippingIt() throws Exception {
        Reply block;
        try (var held = new HeldTransaction(tx, CLUB, serviceBooking("pau", "s09-m-pau", "s09-d-blat", "2026-10-06T10:00", MUN))) {
            block = ringBlock(MUN, "2026-10-06T10:00", "2026-10-06T11:00");
            held.commit();
        }
        System.out.println("E5-T07 R-09-13 ring block while a booking of the ring slot is uncommitted: " + block.outcome());
        assertThat(block.outcome()).as("the S06 side meets the ring-slot write conflict (never 201 over the booking)").isEqualTo("409 STALE_VERSION");
        assertThat(ringBlock(MUN, "2026-10-06T10:00", "2026-10-06T11:00").outcome()).isEqualTo("422 RING_HAS_BOOKINGS");
        assertThat(count("ring_blocks", Criteria.where("ringId").is(MUN).and("state").is("ACTIVE"))).isZero();
    }

    @Test void R_09_13_aBookingDuringAnUncommittedRingBlockIsRetriedAndSeesTheBlock() throws Exception {
        Reply reply;
        try (var held = new HeldTransaction(tx, CLUB, () -> {
            slotLocks.overlapping(MUN, local("2026-10-06T10:00"), local("2026-10-06T11:00"));
            block("s09-held-block", MUN, "2026-10-06T10:00", "2026-10-06T11:00", "BLOCK", "MAINTENANCE", null, null);
        })) {
            reply = whileHeld(held, () -> booking("pau", "s09-d-blat", "2026-10-06T10:00", MUN));
        }
        assertThat(reply.outcome()).isEqualTo("409 SLOT_TAKEN");
        assertThat(reply.body().at("/details/reason").asText()).isEqualTo("RING_BLOCK");
    }

    /** Review E5-T07 #4: the S06↔S09 serialisation document must not make unrelated slots of one ring-day conflict. */
    @Test void R_09_13_lanesOffTwentyBookingsOnTwentySlotsOfOneRingDayNeverExhaustTheirRetries() throws Exception {
        double retried = retries.retries(CONTEXT), exhausted = retries.exhaustions(CONTEXT);
        var replies = ConcurrencySupport.parallel(20, i -> () -> booking("c" + i, "s09-d-c" + i,
                "2026-10-06T" + LocalTime.of(8, 0).plusMinutes(30L * i), MUN));
        var counts = tally(replies);
        double retriedNow = retries.retries(CONTEXT) - retried, exhaustedNow = retries.exhaustions(CONTEXT) - exhausted;
        System.out.println("E5-T07 R-09-13 lanes off, 20 dogs on 20 different slots of one ring-day: " + counts + " · retries " + retriedNow + " · exhausted " + exhaustedNow);
        assertThat((long) exhaustedNow).isEqualTo(counts.getOrDefault("409 STALE_VERSION", 0L));
        assertThat(counts).as("different slots share no document: every booking commits").isEqualTo(Map.of("201", 20L));
        assertThat(active(Criteria.where("ringId").is(MUN))).isEqualTo(20);
    }

    @Test void R_09_13_lanesOffAConcurrentBlockAndBookingOnOneSlotNeverBothCommit() throws Exception {
        for (int round = 0; round < 6; round++) {
            String day = "2026-10-0" + (6 + round / 2), hour = round % 2 == 0 ? "10" : "12";
            String from = day + "T" + hour + ":00", to = day + "T" + (Integer.parseInt(hour) + 1) + ":00";
            final int member = round;
            var replies = ConcurrencySupport.parallel(2, i -> () -> i == 0 ? booking("c" + member, "s09-d-c" + member, from, MUN) : ringBlock(MUN, from, to));
            var booking = replies.get(0); var block = replies.get(1);
            System.out.println("E5-T07 R-09-13 lanes off, round " + round + " booking vs block: " + booking.outcome() + " / " + block.outcome());
            assertThat(booking.outcome()).isIn("201", "409 SLOT_TAKEN", "409 STALE_VERSION");
            assertThat(block.outcome()).isIn("201", "422 RING_HAS_BOOKINGS", "409 STALE_VERSION");
            assertThat(booking.status() == 201 && block.status() == 201).as("never a block over an ACTIVE booking").isFalse();
            long bookings = active(Criteria.where("ringId").is(MUN).and("startsAt").is(Date.from(local(from))));
            long blocks = count("ring_blocks", Criteria.where("ringId").is(MUN).and("state").is("ACTIVE").and("from").is(Date.from(local(from))));
            assertThat(bookings + blocks).isLessThanOrEqualTo(1);
        }
    }

    // S05 side of R-09-13 (review E5-T07 #3): a ring deactivated or no longer open to free training touches every slot
    // of the booking window, so it meets a concurrent booking of the ring in Mongo.
    @Test void T_09_28_aRingMadeNotReservableDuringAnUncommittedBookingConflictsInsteadOfSkippingIt() throws Exception {
        double exhausted = retries.exhaustions(CATALOGS);
        Reply change;
        try (var held = new HeldTransaction(tx, CLUB, serviceBooking("pau", "s09-m-pau", "s09-d-blat", "2026-10-07T18:00", CAD))) {
            change = ringChange(CAD, Map.of("allowsFreeTraining", false));
            held.commit();
        }
        System.out.println("E5-T07 R-09-13 S05 ring change while a booking of the ring stays uncommitted for its whole retry budget: " + change.outcome());
        assertThat(change.outcome()).as("the S05 side meets the ring-slot write conflict (never 200 over the booking)").isEqualTo("409 STALE_VERSION");
        assertThat(retries.exhaustions(CATALOGS) - exhausted).as("E5-T15: the change was retried until its budget ran out").isEqualTo(1);
        assertThat(ringChange(CAD, Map.of("allowsFreeTraining", false)).outcome()).isEqualTo("422 RING_HAS_BOOKINGS");
        assertThat(mongo.findById(CAD, Document.class, "rings").getBoolean("allowsFreeTraining")).isTrue();
        assertThat(active(Criteria.where("ringId").is(CAD))).isEqualTo(1);
    }

    /** E5-T15 (review E5-T07 #1): the S05 ring change is retried like the S06/S08/S09 writers, so it sees the booking that won. */
    @Test void T_09_28_aRingChangeDuringAnUncommittedBookingIsRetriedAndThenSeesTheBooking() throws Exception {
        double before = retries.retries(CATALOGS);
        Reply change;
        try (var held = new HeldTransaction(tx, CLUB, serviceBooking("pau", "s09-m-pau", "s09-d-blat", "2026-10-07T18:00", CAD))) {
            change = whileHeld(held, CATALOGS, () -> ringChange(CAD, Map.of("allowsFreeTraining", false)));
        }
        System.out.println("E5-T15 R-09-13 S05 ring change retried after an uncommitted booking of the ring: " + change.outcome()
                + " · catalogs retries " + (retries.retries(CATALOGS) - before));
        assertThat(change.outcome()).as("the retried change sees the committed booking, never 409 and never 200 over it").isEqualTo("422 RING_HAS_BOOKINGS");
        assertThat(change.body().at("/details/bookings")).singleElement().satisfies(b -> assertThat(b.path("dogName").asText()).isEqualTo("Blat"));
        assertThat(retries.retries(CATALOGS)).isGreaterThan(before);
        assertThat(mongo.findById(CAD, Document.class, "rings").getBoolean("allowsFreeTraining")).isTrue();
        assertThat(active(Criteria.where("ringId").is(CAD))).isEqualTo(1);
    }

    /** E5-T15 (review E5-T07 #1): the other order through the real `CatalogService.update`: the ring change commits first. */
    @Test void T_09_28_aBookingDuringAnUncommittedRealRingChangeIsRetriedAndSeesTheRingNotReservable() throws Exception {
        long version = ((Number) mongo.findById(CAD, Document.class, "rings").get("version")).longValue();
        Reply reply;
        try (var held = new HeldTransaction(tx, CLUB, () -> catalogs.update(CatalogKind.RING, CAD, Map.of("allowsFreeTraining", false, "version", version)))) {
            reply = whileHeld(held, () -> booking("pau", "s09-d-blat", "2026-10-07T18:00", CAD));
        }
        System.out.println("E5-T15 R-09-13 booking retried after an uncommitted CatalogService.update of the ring: " + reply.outcome());
        assertThat(reply.outcome()).isEqualTo("422 RING_NOT_RESERVABLE");
        assertThat(retries.retries(CONTEXT, "write_conflict")).isPositive();
        assertThat(mongo.findById(CAD, Document.class, "rings").getBoolean("allowsFreeTraining")).isFalse();
        assertThat(active(Criteria.where("ringId").is(CAD))).isZero();
    }

    /** S06 `POST /weeks/{id}/validation` by the admin, without an Idempotency-Key (the route takes none), so it retries on its own. */
    Reply validation(String weekId) throws Exception {
        var response = mvc.perform(post("/api/v1/weeks/" + weekId + "/validation").header("Host", HOST).with(as("admin")).contentType("application/json")
                .content("{}")).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsString()));
    }

    /**
     * E5-T15 (R-09-13 on the week paths): a booking that began before its slot's class was generated is still uncommitted
     * when the admin validates the week. The validation touches the ring slots of its DRAFT classes, meets the booking, is
     * retried, and then sees it: `WEEK_INCONSISTENT`, never an ACTIVE class over a live booking.
     */
    @Test void T_06_12_R_09_13_aValidationDuringAnUncommittedBookingOfAGeneratedClassSlotIsRetriedAndSeesTheConflict() throws Exception {
        assertThat(lanes.enabled()).isFalse();
        var week = plannedWeek("2026-10-05", MUN, "TUESDAY", "10:00", "11:00");
        double before = retries.retries(SCHEDULING);
        Reply reply;
        try (var held = new HeldTransaction(tx, CLUB, serviceBooking("pau", "s09-m-pau", "s09-d-blat", "2026-10-06T10:00", MUN))) {
            generate(week); // the generation neither sees nor waits for the uncommitted booking
            reply = whileHeld(held, SCHEDULING, () -> validation(week.weekId()));
        }
        System.out.println("E5-T15 R-09-13 lanes off, week validation while a booking of a generated class's slot is uncommitted: " + reply.outcome()
                + " " + reply.body().at("/details/inconsistencies/0/type").asText() + " · scheduling retries " + (retries.retries(SCHEDULING) - before));
        assertThat(reply.outcome()).isEqualTo("422 WEEK_INCONSISTENT");
        assertThat(reply.body().at("/details/inconsistencies/0/type").asText()).isEqualTo("RING_TRAINING_CONFLICT");
        assertThat(active(Criteria.where("ringId").is(MUN))).isEqualTo(1);
        assertThat(count("class_sessions", Criteria.where("weekId").is(week.weekId()).and("state").is("ACTIVE"))).as("never an ACTIVE class over the booking").isZero();
    }

    @Test void T_09_28_lanesOffAConcurrentRingChangeAndBookingNeverLeaveALiveBookingOnANonReservableRing() throws Exception {
        var rings = List.of(MUN, CEN, CAR, CAD);
        for (int round = 0; round < rings.size(); round++) {
            final int member = round; final String ring = rings.get(round);
            final Map<String, Object> change = round % 2 == 0 ? Map.of("allowsFreeTraining", false) : Map.of("active", false);
            var replies = ConcurrencySupport.parallel(2, i -> () -> i == 0 ? booking("c" + member, "s09-d-c" + member, "2026-10-07T18:00", ring) : ringChange(ring, change));
            var booking = replies.get(0); var ringReply = replies.get(1);
            System.out.println("E5-T07 R-09-13 lanes off, round " + round + " booking vs S05 " + change.keySet() + " on " + ring + ": " + booking.outcome() + " / " + ringReply.outcome());
            assertThat(booking.outcome()).isIn("201", "422 RING_NOT_RESERVABLE", "404 NOT_FOUND", "409 STALE_VERSION");
            assertThat(ringReply.outcome()).isIn("200", "422 RING_HAS_BOOKINGS", "409 STALE_VERSION");
            assertThat(booking.status() == 201 && ringReply.status() == 200).as("never a live booking on a ring that stopped being reservable").isFalse();
            var after = mongo.findById(ring, Document.class, "rings");
            if (!after.getBoolean("active") || !after.getBoolean("allowsFreeTraining")) { assertThat(active(Criteria.where("ringId").is(ring))).isZero(); }
        }
    }
}
