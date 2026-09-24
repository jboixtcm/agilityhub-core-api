package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** S08 WP-08-C over real Mongo: join, limits, leave, both offer modes, claim, demotion, N-15/N-46, silent cancellations, roles. */
class WaitlistIT extends BookingFixtures {
    @Autowired WaitlistService waitlist; @Autowired WaitlistNotifications waitlistNotifications;
    @Autowired com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository waitlistEntries;

    static String id(JsonNode node) { return node.path("id").asText(); }
    long notifications(String code, String channel) { return count("notifications", Criteria.where("code").is(code).and("channel").is(channel)); }
    List<Document> notificationsOf(String code, String channel) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code).and("channel").is(channel)), Document.class, "notifications");
    }
    Document latest(String type) {
        return eventsOf(type).stream().max(Comparator.comparing((Document e) -> e.getDate("occurredAt")).thenComparing(e -> e.getString("_id"))).orElseThrow();
    }
    Instant instant(Document document, String field) { return document.getDate(field) == null ? null : document.getDate(field).toInstant(); }
    void expired(String entryId) {
        publish(new SchedulerEvent(SchedulerEvent.Kind.WaitlistExpired, CLUB, entryId, clock.instant(), Map.of("entryId", entryId, "classId", "s08-last"),
                null, null, DomainEvent.Origin.SYSTEM));
    }

    @Test void T_08_08_T_08_19_joiningNeedsAFullClassAndRespectsTheClassDogWeekAndAcceptanceLimits() throws Exception {
        assertThat(code(join(as("laura"), "last", "s08-d-duna", 422))).isEqualTo("CLASS_NOT_FULL");
        var joanHold = hold(as("joan"), "last", "s08-d-toby", 201);
        assertThat(code(join(as("laura"), "last", "s08-d-duna", 422))).as("full by bookings alone: a hold does not count").isEqualTo("CLASS_NOT_FULL");
        call(DELETE, "/seat-holds/" + id(joanHold), null, as("joan"), 204);
        assertThat(code(join(as("laura"), "later", "s08-d-duna", 422))).isEqualTo("NOT_YET_OPEN");
        var pere = book(as("pere"), "last", "s08-d-nit");
        // waitlist.maxPerClass = 3.
        for (int i = 0; i < 3; i++) { join(as("c" + i), "last", "s08-d-c" + i, 201); }
        var full = join(as("c3"), "last", "s08-d-c3", 409);
        assertThat(code(full)).isEqualTo("WAITLIST_LIMIT"); assertThat(full.at("/details/scope").asText()).isEqualTo("CLASS");
        assertThat(session("last").get("counters", Document.class)).containsEntry("booked", 1).containsEntry("waiting", 3);
        // waitlist.maxPerDogPerWeek = 2 per dog and booking week; with bookings.limitUnit = MEMBER the owner's dogs share it.
        session("fullA", "2026-10-07T12:00", 1, List.of()); session("fullB", "2026-10-08T12:00", 1, List.of()); session("fullC", "2026-10-09T12:00", 1, List.of());
        book(as("c4"), "fullA", "s08-d-c4"); book(as("c5"), "fullB", "s08-d-c5"); book(as("c6"), "fullC", "s08-d-c6");
        join(as("laura"), "fullA", "s08-d-duna", 201); join(as("laura"), "fullB", "s08-d-duna", 201);
        var week = join(as("laura"), "fullC", "s08-d-duna", 409);
        assertThat(code(week)).isEqualTo("WAITLIST_LIMIT"); assertThat(week.at("/details/scope").asText()).isEqualTo("DOG_WEEK");
        parameter("bookings.limitUnit", "MEMBER");
        assertThat(join(as("laura"), "fullC", "s08-d-rock", 409).at("/details/scope").asText()).isEqualTo("DOG_WEEK");
        parameter("bookings.limitUnit", "DOG");
        join(as("laura"), "fullC", "s08-d-rock", 201);
        // «Ja ha fet classe» → 1: Pere has Wednesday done (and Thursday's «last», still swappable).
        book(as("pere"), "wed", "s08-d-nit");
        clock.setInstant(local("2026-10-08T09:00"));
        join(as("pere"), "fullC", "s08-d-nit", 201);
        assertThat(join(as("pere"), "fullB", "s08-d-nit", 409).at("/details/scope").asText()).isEqualTo("DOG_WEEK");
        // Acceptance: at the limit with nothing swappable (Wednesday done, «last» inside the 4 h threshold) → BOOKING_LIMIT_REACHED.
        clock.setInstant(local("2026-10-08T16:30"));
        var fullC = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("classSessionId").is("s08-fullC").and("dogId").is("s08-d-nit")), Document.class, "waitlist_entries");
        call(POST, "/waitlist-entries/" + fullC.getString("_id") + "/cancellation", null, as("pere"), 200);
        var limit = join(as("pere"), "fullC", "s08-d-nit", 409);
        assertThat(code(limit)).isEqualTo("BOOKING_LIMIT_REACHED"); assertThat(limit.at("/details/swappable")).isEmpty();
        assertThat(limit.at("/details/notSelectable")).extracting(n -> n.path("reason").asText()).containsExactly("DONE", "LATE_WINDOW");
        // The eligibility chain runs too: an expired pack rejects the entry (R-08-17).
        clock.setInstant(NOW); openPack("s08-m-joan", "s08-d-toby", 10, 5, LocalDate.parse("2026-10-07"));
        assertThat(code(join(as("joan"), "fullC", "s08-d-toby", 422))).isEqualTo("PACK_EMPTY");
        assertThat(booking(id(pere))).containsEntry("state", "ACTIVE");
    }

    @Test void T_08_19_joinLeaveAndADirectBookingConsolidatesTheEntry() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        var duna = join(as("laura"), "last", "s08-d-duna", 201);
        assertThat(duna.path("state").asText()).isEqualTo("ACTIVE"); assertThat(entry(id(duna)).getInteger("position")).isEqualTo(1);
        assertThat(duna.path("position").isNull()).as("ALL_AT_ONCE: the queue number is not shown").isTrue();
        assertThat(call(GET, "/waitlist-entries/" + id(duna), null, as("laura"), 200).path("position").isNull()).isTrue();
        assertThat(duna.path("memberId").asText()).isEqualTo("s08-m-laura"); assertThat(duna.path("dogName").asText()).isEqualTo("Duna");
        assertThat(duna.at("/classSession/startsAtLocal").asText()).isEqualTo("2026-10-08T20:00"); assertThat(duna.path("confirmBy").isNull()).isTrue();
        assertThat(entry(id(duna))).containsEntry("bookingWeekKey", "2026-10-04").containsEntry("accountId", "s08-laura");
        assertThat(instant(entry(id(duna)), "classStartsAt")).isEqualTo(local("2026-10-08T20:00"));
        assertThat(eventsOf("WaitlistJoined").getFirst().get("payload", Document.class)).containsOnlyKeys("entryId", "classId", "memberId", "dogId");
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").getString("lastDogForClass")).isEqualTo("s08-d-duna");
        // Laura joins for Joan's Toby (family group): the entry is the owner's, the account and lastDogForClass are Laura's.
        var toby = join(as("laura"), "last", "s08-d-toby", 201);
        assertThat(toby.path("memberId").asText()).isEqualTo("s08-m-joan"); assertThat(entry(id(toby)).getInteger("position")).isEqualTo(2);
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").getString("lastDogForClass")).isEqualTo("s08-d-toby");
        assertThat(code(join(as("joan"), "last", "s08-d-toby", 409))).isEqualTo("ALREADY_ON_WAITLIST");
        assertThat(code(join(as("pere"), "last", "s08-d-nit", 409))).isEqualTo("ALREADY_BOOKED");
        assertThat(code(join(as("pere"), "last", "s08-d-duna", 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(session("last").get("counters", Document.class)).containsEntry("waiting", 2);
        // Leaving (the group member): CANCELLED{MEMBER} + WaitlistLeft; a second time → WAITLIST_ENTRY_NOT_LIVE.
        var left = call(POST, "/waitlist-entries/" + id(toby) + "/cancellation", null, as("joan"), 200);
        assertThat(left.path("state").asText()).isEqualTo("CANCELLED"); assertThat(left.path("cancelReason").asText()).isEqualTo("MEMBER");
        assertThat(code(call(POST, "/waitlist-entries/" + id(toby) + "/cancellation", null, as("joan"), 422))).isEqualTo("WAITLIST_ENTRY_NOT_LIVE");
        assertThat(eventsOf("WaitlistLeft")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("entryId", id(toby)));
        var again = join(as("joan"), "last", "s08-d-toby", 201);
        assertThat(entry(id(again)).getInteger("position")).as("max + 1 over every state").isEqualTo(3);
        // A direct booking of a waiting dog consolidates its entry (BOOKED_DIRECTLY in the trail, state CONSOLIDATED).
        cancel(as("pere"), id(pere), 200);
        var direct = book(as("laura"), "last", "s08-d-duna");
        assertThat(entry(id(duna))).containsEntry("state", "CONSOLIDATED").containsEntry("cancelReason", "BOOKED_DIRECTLY").containsEntry("bookingId", id(direct));
        assertThat(booking(id(direct))).containsEntry("waitlistEntryId", id(duna));
        assertThat(eventsOf("WaitlistConsolidated").getFirst().get("payload", Document.class)).containsEntry("entryId", id(duna)).containsEntry("bookingId", id(direct));
        assertThat(session("last").get("counters", Document.class)).containsEntry("booked", 1).containsEntry("waiting", 1);
        // The club removes an entry from D4/D12: CANCELLED{ADMIN}.
        assertThat(call(POST, "/waitlist-entries/" + id(again) + "/cancellation", null, as("admin"), 200).path("cancelReason").asText()).isEqualTo("ADMIN");
        assertThat(session("last").get("counters", Document.class)).containsEntry("waiting", 0);
        assertThat(count("waitlist_entries", new Criteria())).as("nothing is ever deleted (BR-12)").isEqualTo(3);
    }

    @Test void T_08_20_allAtOnceNotifiesEveryWaitingMemberOnceTheFirstClaimWinsAndTheRestReturnToActiveWithN46() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), toby = id(join(as("joan"), "last", "s08-d-toby", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        cancel(as("pere"), id(pere), 200); // 34 h before: in time and above the 30 min notice threshold
        assertThat(eventsOf("SeatReleased").getFirst().get("payload", Document.class)).containsEntry("notifyWaitlist", true).containsEntry("freeSeats", 1);
        dispatch();
        for (String entry : List.of(duna, toby, c0)) {
            assertThat(entry(entry)).containsEntry("state", "NOTIFIED"); assertThat(instant(entry(entry), "notifiedAt")).isEqualTo(NOW);
            assertThat(entry(entry).get("confirmBy")).isNull();
        }
        var notified = eventsOf("WaitlistNotified");
        assertThat(notified).hasSize(1);
        assertThat(notified.getFirst().get("payload", Document.class)).containsEntry("mode", "ALL_AT_ONCE").containsEntry("classId", "s08-last").doesNotContainKey("confirmBy");
        assertThat(notified.getFirst().get("payload", Document.class).getList("entryIds", String.class)).containsExactly(duna, toby, c0);
        // N-15 once per entry: APP + SMS intent + PUSH intent, action CLAIM_SEAT.
        assertThat(notificationsOf("N-15", "APP")).extracting(n -> n.getString("accountId")).containsExactlyInAnyOrder("s08-laura", "s08-joan", "s08-c0");
        assertThat(notificationsOf("N-15", "APP")).allSatisfy(n -> assertThat(n.get("variables", Document.class)).containsEntry("action", "CLAIM_SEAT")
                .containsEntry("mode", "ALL_AT_ONCE").containsKeys("dog_name", "class_date", "class_time", "entityId").doesNotContainKey("confirm_by"));
        assertThat(notificationsOf("N-15", "SMS")).hasSize(3).allSatisfy(n -> {
            assertThat(n.getString("status")).isEqualTo("QUEUED"); assertThat(n.getString("body")).hasSizeLessThanOrEqualTo(160).matches("[\\x20-\\x7E\\r\\n]*");
        });
        assertThat(notificationsOf("N-15", "PUSH")).hasSize(3).allSatisfy(n -> assertThat(n.getString("status")).isEqualTo("QUEUED"));
        // Redelivery: the consumer and the notification find nothing new.
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.offerSeats("s08-last", 1)).isZero(); }
        waitlistNotifications.offered(notified.getFirst().getString("_id"), new BookingEvent(BookingEvent.Kind.WaitlistNotified, CLUB, "s08-last", NOW,
                Map.of("entryIds", List.of(duna, toby, c0), "classId", "s08-last", "mode", "ALL_AT_ONCE"), null, null, DomainEvent.Origin.SYSTEM));
        dispatch();
        assertThat(events("WaitlistNotified")).isEqualTo(1); assertThat(notifications("N-15", "APP")).isEqualTo(3); assertThat(notifications("N-15", "SMS")).isEqualTo(3);
        // Joan holds through the offer; Laura then finds the seat held (SEAT_TAKEN, not CLASS_FULL); Joan claims.
        var held = holdFor(as("joan"), "last", "s08-d-toby", toby, 201);
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 409))).isEqualTo("SEAT_TAKEN");
        var claimed = claim(as("joan"), toby, id(held), null, 201);
        assertThat(claimed.path("state").asText()).isEqualTo("ACTIVE"); assertThat(claimed.path("origin").asText()).isEqualTo("APP");
        assertThat(entry(toby)).containsEntry("state", "CONSOLIDATED").containsEntry("bookingId", id(claimed)); assertThat(entry(toby).get("cancelReason")).isNull();
        assertThat(booking(id(claimed))).containsEntry("waitlistEntryId", toby);
        for (String entry : List.of(duna, c0)) {
            assertThat(entry(entry)).containsEntry("state", "ACTIVE"); assertThat(instant(entry(entry), "notifiedAt")).as("kept for the record").isEqualTo(NOW);
        }
        assertThat(session("last").get("counters", Document.class)).containsEntry("booked", 1).containsEntry("waiting", 2);
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 409))).as("the offer was taken").isEqualTo("SEAT_TAKEN");
        assertThat(code(claim(as("laura"), duna, "s08-no-hold", null, 409))).isEqualTo("SEAT_TAKEN");
        dispatch(); dispatch();
        assertThat(notificationsOf("N-46", "APP")).extracting(n -> n.getString("accountId")).containsExactlyInAnyOrder("s08-laura", "s08-c0");
        assertThat(notificationsOf("N-46", "APP")).allSatisfy(n -> assertThat(n.get("variables", Document.class)).containsKeys("dog_name", "class_date", "class_time"));
        assertThat(count("notifications", Criteria.where("code").is("N-46").and("channel").ne("APP"))).as("N-46 is APP only").isZero();
        assertThat(notificationsOf("N-04", "APP")).extracting(n -> n.getString("accountId")).contains("s08-joan");
        // A new release notifies them again (a new offer, new N-15; the old N-46 is not repeated).
        clock.advance(Duration.ofMinutes(1));
        cancel(as("joan"), id(claimed), 200); dispatch();
        assertThat(entry(duna)).containsEntry("state", "NOTIFIED"); assertThat(instant(entry(duna), "notifiedAt")).isEqualTo(NOW.plusSeconds(60));
        assertThat(events("WaitlistNotified")).isEqualTo(2); assertThat(notifications("N-15", "APP")).isEqualTo(5); assertThat(notifications("N-46", "APP")).isEqualTo(2);
    }

    @Test void T_08_21_fifoOffersTheFirstEntryUntilConfirmByAndTheExpiryMovesToTheNext() throws Exception {
        parameter("waitlist.mode", "FIFO");
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), toby = id(join(as("joan"), "last", "s08-d-toby", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        cancel(as("pere"), id(pere), 200); dispatch();
        assertThat(entry(duna)).containsEntry("state", "NOTIFIED"); assertThat(instant(entry(duna), "confirmBy")).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(entry(toby)).containsEntry("state", "ACTIVE"); assertThat(entry(c0)).containsEntry("state", "ACTIVE");
        assertThat(eventsOf("WaitlistNotified")).singleElement().satisfies(e -> {
            assertThat(e.get("payload", Document.class)).containsEntry("mode", "FIFO").containsKey("confirmBy");
            assertThat(e.get("payload", Document.class).getList("entryIds", String.class)).containsExactly(duna);
        });
        assertThat(notificationsOf("N-15", "APP")).singleElement().satisfies(n -> assertThat(n.get("variables", Document.class))
                .containsEntry("confirm_by", "10:30").containsEntry("mode", "FIFO").containsEntry("action", "CLAIM_SEAT"));
        assertThat(notificationsOf("N-15", "SMS")).singleElement().satisfies(n -> assertThat(n.getString("body")).contains("10:30"));
        var detail = call(GET, "/waitlist-entries/" + duna, null, as("laura"), 200);
        assertThat(detail.path("confirmBy").asText()).isEqualTo(NOW.plus(Duration.ofMinutes(30)).toString()); assertThat(detail.path("position").asInt()).isEqualTo(1);
        // confirmBy passed: the offer is over for the hold and the claim.
        clock.setInstant(NOW.plus(Duration.ofMinutes(30)));
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 422))).isEqualTo("WAITLIST_OFFER_EXPIRED");
        assertThat(code(claim(as("laura"), duna, "s08-no-hold", null, 422))).isEqualTo("WAITLIST_OFFER_EXPIRED");
        // S15 P6 (E5-T05) expires the entry and emits WaitlistExpired → the next one, with its own window.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(duna)), new Update().set("state", "EXPIRED"), "waitlist_entries");
        expired(duna); dispatch();
        assertThat(entry(toby)).containsEntry("state", "NOTIFIED"); assertThat(instant(entry(toby), "confirmBy")).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(entry(c0)).containsEntry("state", "ACTIVE");
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 422))).isEqualTo("WAITLIST_OFFER_EXPIRED");
        clock.advance(Duration.ofMinutes(15));
        var claimed = claim(as("joan"), toby, id(holdFor(as("joan"), "last", "s08-d-toby", toby, 201)), null, 201);
        assertThat(entry(toby)).containsEntry("state", "CONSOLIDATED"); assertThat(entry(c0)).as("FIFO never demotes").containsEntry("state", "ACTIVE");
        // No time left: a release 30 minutes or less before the start notifies nobody.
        clock.setInstant(local("2026-10-08T19:30"));
        cancel(as("joan"), id(claimed), 200);
        assertThat(latest("SeatReleased").get("payload", Document.class)).containsEntry("notifyWaitlist", false).containsEntry("minutesBefore", 30);
        dispatch();
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.offerNext("s08-last", null)).isZero(); assertThat(waitlist.offerSeats("s08-last", 1)).isZero(); }
        assertThat(entry(c0)).containsEntry("state", "ACTIVE"); assertThat(events("WaitlistNotified")).isEqualTo(2);
    }

    @Test void T_08_22_claimNeedsTheOfferedHoldAndStillRespectsTheWeeklyLimitWithSwap() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        var wed = book(as("laura"), "wed", "s08-d-duna"); var fri = book(as("laura"), "fri", "s08-d-duna");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)); // at the W0 limit with two swappable bookings: acceptable
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 422))).isEqualTo("WAITLIST_NOT_NOTIFIED");
        assertThat(code(claim(as("laura"), duna, "s08-no-hold", null, 422))).isEqualTo("WAITLIST_NOT_NOTIFIED");
        cancel(as("pere"), id(pere), 200); dispatch();
        assertThat(entry(duna)).containsEntry("state", "NOTIFIED");
        // A plain hold (without waitlistEntryId) cannot be claimed.
        var plain = hold(as("laura"), "last", "s08-d-duna", 201);
        assertThat(code(claim(as("laura"), duna, id(plain), null, 422))).isEqualTo("WAITLIST_NOT_NOTIFIED");
        // Through the offer: the waiting-list limits do not apply, BR-01 does → limit.swappable and a swap.
        var offered = holdFor(as("laura"), "last", "s08-d-duna", duna, 201);
        assertThat(offered.at("/limit/reached").asBoolean()).isTrue();
        assertThat(offered.at("/limit/swappable")).extracting(s -> s.path("bookingId").asText()).containsExactly(id(wed), id(fri));
        assertThat(code(claim(as("laura"), duna, id(offered), null, 422))).isEqualTo("SWAP_NOT_ALLOWED");
        assertThat(code(claim(as("joan"), duna, id(offered), null, 409))).as("the hold belongs to Laura's account").isEqualTo("SEAT_HOLD_EXPIRED");
        String key = UUID.randomUUID().toString();
        var claimed = claim(as("laura"), duna, id(offered), id(fri), 201, key);
        assertThat(claimed.path("swapFromBookingId").asText()).isEqualTo(id(fri));
        assertThat(booking(id(fri))).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "SWAP");
        assertThat(entry(duna)).containsEntry("state", "CONSOLIDATED").containsEntry("bookingId", id(claimed));
        // The same Idempotency-Key replays the same response.
        assertThat(id(claim(as("laura"), duna, id(offered), id(fri), 201, key))).isEqualTo(id(claimed));
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-last").and("state").is("ACTIVE"))).isEqualTo(1);
        // At the limit with nothing swappable the offered hold is refused: BOOKING_LIMIT_REACHED.
        session("sun", "2026-10-11T19:00", 1, List.of()); book(as("c1"), "sun", "s08-d-c1");
        book(as("joan"), "wed", "s08-d-toby"); book(as("joan"), "sat", "s08-d-toby");
        String toby = id(join(as("joan"), "sun", "s08-d-toby", 201));
        clock.setInstant(local("2026-10-10T07:00")); // Wednesday done, Saturday 09:00 inside the 4 h threshold
        cancel(as("c1"), mongo.findOne(Query.query(Criteria.where("dogId").is("s08-d-c1")), Document.class, "bookings").getString("_id"), 200); dispatch();
        assertThat(entry(toby)).containsEntry("state", "NOTIFIED");
        var refused = holdFor(as("joan"), "sun", "s08-d-toby", toby, 409);
        assertThat(code(refused)).isEqualTo("BOOKING_LIMIT_REACHED"); assertThat(refused.at("/details/swappable")).isEmpty();
    }

    @Test void T_08_34_aDuplicateWaitlistExpiredProducesASingleOffer() throws Exception {
        parameter("waitlist.mode", "FIFO");
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), toby = id(join(as("joan"), "last", "s08-d-toby", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        cancel(as("pere"), id(pere), 200); dispatch();
        clock.advance(Duration.ofMinutes(31));
        // The same expiry delivered twice; the entry is still NOTIFIED, so the consumer expires it defensively (S15's own step).
        expired(duna); expired(duna); dispatch();
        assertThat(entry(duna)).containsEntry("state", "EXPIRED");
        assertThat(entry(toby)).containsEntry("state", "NOTIFIED"); assertThat(entry(c0)).containsEntry("state", "ACTIVE");
        assertThat(events("WaitlistNotified")).isEqualTo(2); assertThat(notifications("N-15", "APP")).isEqualTo(2);
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.offerNext("s08-last", duna)).isZero(); }
        assertThat(entry(c0)).containsEntry("state", "ACTIVE");
        assertThat(count("domain_events", Criteria.where("status").is("FAILED"))).isZero();
    }

    @Test void T_08_45_fifoWithTwoSeatsReleasedAtOnceNotifiesTheFirstTwoEachWithItsConfirmBy() throws Exception {
        parameter("waitlist.mode", "FIFO");
        session("two", "2026-10-08T12:00", 2, List.of());
        var pere = book(as("pere"), "two", "s08-d-nit"); var joan = book(as("joan"), "two", "s08-d-toby");
        String duna = id(join(as("laura"), "two", "s08-d-duna", 201)), c0 = id(join(as("c0"), "two", "s08-d-c0", 201)), c1 = id(join(as("c1"), "two", "s08-d-c1", 201));
        cancel(as("pere"), id(pere), 200); cancel(as("joan"), id(joan), 200);
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.offerSeats("s08-two", 2)).isEqualTo(2); }
        assertThat(entry(duna)).containsEntry("state", "NOTIFIED"); assertThat(entry(c0)).containsEntry("state", "NOTIFIED");
        assertThat(entry(c1)).containsEntry("state", "ACTIVE");
        assertThat(eventsOf("WaitlistNotified")).hasSize(2).allSatisfy(e -> {
            assertThat(e.get("payload", Document.class).getList("entryIds", String.class)).hasSize(1);
            assertThat(e.get("payload", Document.class).getDate("confirmBy").toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        });
        dispatch(); // the two SeatReleased deliveries find both seats already on offer
        assertThat(events("WaitlistNotified")).isEqualTo(2); assertThat(entry(c1)).containsEntry("state", "ACTIVE");
        assertThat(notifications("N-15", "APP")).isEqualTo(2);
        // The third only after an expiry.
        clock.advance(Duration.ofMinutes(30));
        publish(new SchedulerEvent(SchedulerEvent.Kind.WaitlistExpired, CLUB, duna, clock.instant(), Map.of("entryId", duna), null, null, DomainEvent.Origin.SYSTEM));
        dispatch();
        assertThat(entry(duna)).containsEntry("state", "EXPIRED"); assertThat(entry(c1)).containsEntry("state", "NOTIFIED");
        assertThat(events("WaitlistNotified")).isEqualTo(3);
    }

    @Test void R_08_16_R_08_21_silentCancellationsSweepStartedClassesMembersWhoLeaveAndClassCancellations() throws Exception {
        book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        session("early", "2026-10-06T12:00", 1, List.of()); book(as("c1"), "early", "s08-d-c1");
        String toby = id(join(as("joan"), "early", "s08-d-toby", 201));
        long events = count("domain_events", new Criteria());
        // S15 P8 sweep (scheduled by E6): only the started class, silently.
        clock.setInstant(local("2026-10-06T12:00"));
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.sweepStarted(clock.instant())).isEqualTo(1); assertThat(waitlist.sweepStarted(clock.instant())).isZero(); }
        assertThat(entry(toby)).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "CLASS_STARTED");
        assertThat(entry(duna)).containsEntry("state", "ACTIVE");
        assertThat(session("early").get("counters", Document.class)).containsEntry("waiting", 0);
        // S15 P5c / S13: the entries of a member who leaves, silently (MEMBER_LEFT, S15 §13 proposal).
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.cancelByMember("s08-m-laura", WaitlistCancelReason.MEMBER_LEFT)).isEqualTo(1); }
        assertThat(entry(duna)).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "MEMBER_LEFT");
        assertThat(session("last").get("counters", Document.class)).containsEntry("waiting", 1);
        assertThat(count("domain_events", new Criteria())).as("no event of their own").isEqualTo(events);
        // R-08-21: S06 moves the class → the live entry's denormalised start and week follow.
        var moved = local("2026-10-11T21:00");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-last")), new Update().set("startsAt", Date.from(moved)).set("endsAt", Date.from(moved.plusSeconds(3600))), "class_sessions");
        publish(new com.agilityhub.core.support.TestEvent("ClassSessionUpdated", null, CLUB, "ClassSession", "s08-last", NOW, Map.of("classId", "s08-last"), null, null, DomainEvent.Origin.BACKOFFICE));
        dispatch();
        assertThat(instant(entry(c0), "classStartsAt")).isEqualTo(moved); assertThat(entry(c0)).containsEntry("bookingWeekKey", "2026-10-11");
        // S06 cancels the class: the remaining entry is CANCELLED{CLASS_CANCELLED} inside its transaction and listed in waitlistIds.
        call(POST, "/class-sessions/s08-last/cancellation", Map.of("reason", "CLUB_MANUAL", "adminText", "Fictional cancellation"), as("admin"), 200, UUID.randomUUID().toString());
        assertThat(entry(c0)).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "CLASS_CANCELLED");
        assertThat(eventsOf("ClassCancelledByClub").getFirst().get("payload", Document.class).getList("waitlistIds", String.class)).containsExactly(c0);
    }

    @Test void T_08_26_waitlistRolesTenantImpersonationAndModuleOff() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201));
        call(GET, "/waitlist-entries/" + duna, null, as("laura"), 200); call(GET, "/waitlist-entries/" + duna, null, as("joan"), 200); // family group
        assertThat(code(call(GET, "/waitlist-entries/" + duna, null, as("pere"), 404))).isEqualTo("NOT_FOUND");
        call(GET, "/waitlist-entries/" + duna, null, as("inst"), 200); call(GET, "/waitlist-entries/" + duna, null, as("admin"), 200);
        call(GET, "/waitlist-entries/" + duna, null, null, 401);
        assertThat(code(call(POST, "/waitlist-entries/" + duna + "/cancellation", null, as("pere"), 404))).isEqualTo("NOT_FOUND");
        call(POST, "/waitlist-entries/" + duna + "/cancellation", null, as("inst"), 403);
        assertThat(code(claim(as("pere"), duna, "s08-no-hold", null, 404))).isEqualTo("NOT_FOUND");
        call(POST, "/waitlist-entries", Map.of("classSessionId", "s08-last", "dogId", "s08-d-duna"), as("admin"), 403);
        var list = call(GET, "/class-sessions/s08-last/waitlist-entries", null, as("inst"), 200).path("items");
        assertThat(list).singleElement().satisfies(e -> { assertThat(e.path("id").asText()).isEqualTo(duna); assertThat(e.path("dogName").asText()).isEqualTo("Duna"); });
        call(GET, "/class-sessions/s08-last/waitlist-entries", null, as("laura"), 403);
        // Impersonation: the admin joins for Laura (the entry's account is the admin's) and leaves it (cancelReason ADMIN).
        var rock = join(impersonating("admin", "s08-m-laura"), "last", "s08-d-rock", 201);
        assertThat(entry(id(rock))).containsEntry("accountId", "s08-admin").containsEntry("memberId", "s08-m-laura");
        assertThat(eventsOf("WaitlistJoined")).filteredOn(e -> id(rock).equals(e.getString("aggregateId"))).singleElement()
                .satisfies(e -> assertThat(e.getString("origin")).isEqualTo("BACKOFFICE"));
        var left = call(POST, "/waitlist-entries/" + id(rock) + "/cancellation", null, impersonating("admin", "s08-m-laura"), 200);
        assertThat(left.path("cancelReason").asText()).isEqualTo("ADMIN");
        call(GET, "/class-sessions/s08-last/waitlist-entries", null, impersonating("admin", "s08-m-laura"), 403);
        // Club B never sees club A's entries.
        var otherClub = jwt().jwt(j -> j.subject("s08-admin").claim("clubId", OTHER).claim("memberId", "s08-m-admin")).authorities(() -> "ROLE_ADMIN");
        assertThat(mvc.perform(get("/api/v1/waitlist-entries/" + duna).header("Host", OTHER_HOST).with(otherClub)).andReturn().getResponse().getStatus()).isEqualTo(404);
        // WAITLIST off: 404 everywhere, no notice on release, counters.waiting 0.
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.WAITLIST).toArray(Module[]::new));
        assertThat(code(call(GET, "/waitlist-entries/" + duna, null, as("laura"), 404))).isEqualTo("MODULE_DISABLED");
        assertThat(code(join(as("joan"), "last", "s08-d-toby", 404))).isEqualTo("MODULE_DISABLED");
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 404))).isEqualTo("MODULE_DISABLED");
        assertThat(code(call(GET, "/class-sessions/s08-last/waitlist-entries", null, as("admin"), 404))).isEqualTo("MODULE_DISABLED");
        cancel(as("pere"), id(pere), 200);
        assertThat(latest("SeatReleased").get("payload", Document.class)).containsEntry("notifyWaitlist", false);
        assertThat(session("last").get("counters", Document.class)).containsEntry("waiting", 0);
        dispatch();
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.offerSeats("s08-last", 1)).isZero(); }
        assertThat(entry(duna)).containsEntry("state", "ACTIVE");
        // WAITLIST back on (S02 publishes ClubModulesChanged): counters.waiting is recounted, once.
        modules(Module.values());
        var before = Arrays.stream(Module.values()).filter(m -> m != Module.WAITLIST).map(Enum::name).sorted().toList();
        var after = Arrays.stream(Module.values()).map(Enum::name).sorted().toList();
        for (int i = 0; i < 2; i++) {
            publish(new com.agilityhub.core.platform.domain.events.ClubModulesChanged(CLUB, clock.instant(), Map.of("diff", Map.of("modules", Map.of("before", before, "after", after))),
                    "s08-admin", null, DomainEvent.Origin.BACKOFFICE));
            dispatch();
            assertThat(session("last").get("counters", Document.class)).containsEntry("waiting", 1);
        }
        assertThat(count("domain_events", Criteria.where("status").is("FAILED"))).isZero();
    }

    @Test void R_08_13_anOrdinaryBookingNeitherLocksNorWritesForN46() throws Exception {
        book(as("pere"), "last", "s08-d-nit"); join(as("laura"), "last", "s08-d-duna", 201); // waiting, never offered
        book(as("joan"), "wed", "s08-d-toby"); book(as("c0"), "thu", "s08-d-c0");
        var before = mongo.findAll(Document.class, "seat_locks");
        dispatch();
        assertThat(mongo.findAll(Document.class, "seat_locks")).as("the N-46 consumers returned before any class lock").isEqualTo(before);
        assertThat(notifications("N-46", "APP")).isZero(); assertThat(count("domain_events", Criteria.where("status").is("FAILED"))).isZero();
    }

    @Test void R_08_13_noN46ForAnOfferWhoseN15WasNeverDelivered() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), toby = id(join(as("joan"), "last", "s08-d-toby", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        cancel(as("pere"), id(pere), 200);
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.offerSeats("s08-last", 1)).isEqualTo(3); } // offered, WaitlistNotified still in the outbox
        claim(as("joan"), toby, id(holdFor(as("joan"), "last", "s08-d-toby", toby, 201)), null, 201);
        assertThat(entry(duna)).containsEntry("state", "ACTIVE"); assertThat(entry(c0)).containsEntry("state", "ACTIVE");
        dispatch(); dispatch();
        assertThat(notifications("N-15", "APP")).as("the demoted entries are skipped").isZero();
        assertThat(notifications("N-46", "APP")).as("nobody heard of the offer, nobody is told it is gone").isZero();
    }

    @Test void R_08_13_theDeliveredOfferIsRecordedOnTheEntryAndTheSeatTakenAfterwardsSendsN46() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        cancel(as("pere"), id(pere), 200); dispatch();
        for (String entry : List.of(duna, c0)) {
            assertThat(entry(entry)).containsEntry("state", "NOTIFIED");
            assertThat(instant(entry(entry), "offerNotifiedAt")).as("N-15 delivered with this offer").isEqualTo(instant(entry(entry), "notifiedAt"));
        }
        assertThat(notifications("N-15", "APP")).isEqualTo(2);
        book(as("joan"), "last", "s08-d-toby"); // the seat is taken after the delivery
        for (String entry : List.of(duna, c0)) {
            assertThat(entry(entry)).containsEntry("state", "ACTIVE"); assertThat(instant(entry(entry), "offerNotifiedAt")).as("kept by the demotion").isEqualTo(NOW);
        }
        dispatch();
        assertThat(notificationsOf("N-46", "APP")).extracting(n -> n.getString("accountId")).containsExactlyInAnyOrder("s08-laura", "s08-c0");
    }

    @Test void R_08_13_aSeatTakenBeforeTheN15ConsumerRunsGetsNeitherALateN15NorN46() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        cancel(as("pere"), id(pere), 200);
        try (var t = TenantContext.open(CLUB)) { assertThat(waitlist.offerSeats("s08-last", 1)).isEqualTo(2); } // WaitlistNotified still in the outbox
        book(as("joan"), "last", "s08-d-toby"); // the seat is taken first: both entries are demoted
        var notified = eventsOf("WaitlistNotified").getFirst();
        // The N-15 consumer runs late (directly, then again through the outbox): the conditional update misses.
        waitlistNotifications.offered(notified.getString("_id"), new BookingEvent(BookingEvent.Kind.WaitlistNotified, CLUB, "s08-last", NOW,
                Map.of("entryIds", List.of(duna, c0), "classId", "s08-last", "mode", "ALL_AT_ONCE"), null, null, DomainEvent.Origin.SYSTEM));
        dispatch(); dispatch();
        for (String entry : List.of(duna, c0)) {
            assertThat(entry(entry)).containsEntry("state", "ACTIVE"); assertThat(entry(entry).get("offerNotifiedAt")).isNull();
        }
        assertThat(count("notifications", Criteria.where("code").is("N-15"))).as("no late N-15 on any channel").isZero();
        assertThat(notifications("N-46", "APP")).isZero();
        try (var t = TenantContext.open(CLUB)) {
            assertThat(waitlistEntries.markOfferNotified(duna, NOW)).as("a demoted entry is never marked").isFalse();
        }
    }

    @Test void R_08_13_aHoldReleasedWithoutABookingSendsNoN46AndChangesNothing() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)), toby = id(join(as("joan"), "last", "s08-d-toby", 201));
        cancel(as("pere"), id(pere), 200); dispatch();
        var held = holdFor(as("joan"), "last", "s08-d-toby", toby, 201);
        // The class is made full behind the consumer's back, so a demotion would happen if the release were handled.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-last")), new Update().set("capacity", 0), "class_sessions");
        call(DELETE, "/seat-holds/" + id(held), null, as("joan"), 204);
        assertThat(eventsOf("SeatHoldReleased")).filteredOn(e -> id(held).equals(e.getString("aggregateId"))).as("the release, not a confirmation").hasSize(1);
        var entriesBefore = List.of(entry(duna), entry(toby)); var locksBefore = mongo.findAll(Document.class, "seat_locks");
        dispatch();
        assertThat(List.of(entry(duna), entry(toby))).as("no demotion, no write").isEqualTo(entriesBefore);
        assertThat(mongo.findAll(Document.class, "seat_locks")).as("no class lock taken").isEqualTo(locksBefore);
        assertThat(notifications("N-46", "APP")).isZero(); assertThat(count("domain_events", Criteria.where("status").is("FAILED"))).isZero();
        // A hold that expires emits no event at all (TTL index, R-08-07): nothing to consume.
    }

    @Test void R_08_13_R_08_18_aPayToBookBookingTakingTheLastSeatSendsN46WhenTheSeatIsTaken() throws Exception {
        payToBook();
        var c1 = book(as("c1"), "last", "s08-d-c1");
        String toby = id(join(as("joan"), "last", "s08-d-toby", 201)), c0 = id(join(as("c0"), "last", "s08-d-c0", 201));
        cancel(as("c1"), id(c1), 200); dispatch();
        assertThat(entry(toby)).containsEntry("state", "NOTIFIED"); assertThat(notifications("N-15", "APP")).isEqualTo(2);
        // Pere (PAY_TO_BOOK, not waiting) books the last seat directly: PAYMENT_PENDING, and the offer is gone now.
        var pending = book(as("pere"), "last", "s08-d-nit");
        assertThat(pending.path("state").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(entry(toby)).containsEntry("state", "ACTIVE"); assertThat(entry(c0)).containsEntry("state", "ACTIVE");
        dispatch();
        assertThat(eventsOf("BookingCreated")).as("not paid yet").noneMatch(e -> id(pending).equals(e.getString("aggregateId")));
        assertThat(notificationsOf("N-46", "APP")).extracting(n -> n.getString("accountId")).containsExactlyInAnyOrder("s08-joan", "s08-c0");
    }

    @Test void R_08_15_theClaimOfADemotedEntryAnswersLikeItsHold() throws Exception {
        var pere = book(as("pere"), "last", "s08-d-nit");
        String duna = id(join(as("laura"), "last", "s08-d-duna", 201)); join(as("c0"), "last", "s08-d-c0", 201);
        cancel(as("pere"), id(pere), 200); dispatch();
        var joan = book(as("joan"), "last", "s08-d-toby"); // a direct booking takes the offered seat
        assertThat(entry(duna)).containsEntry("state", "ACTIVE");
        assertThat(code(claim(as("laura"), duna, "s08-no-hold", null, 409))).isEqualTo("SEAT_TAKEN");
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 409))).isEqualTo("SEAT_TAKEN");
        dispatch();
        assertThat(notificationsOf("N-46", "APP")).extracting(n -> n.getString("accountId")).containsExactlyInAnyOrder("s08-laura", "s08-c0");
        // The seat is freed again inside the notice threshold (raised here to 5000 min; the class starts in 58 h): no new offer.
        parameter("waitlist.notifyThresholdMinutes", 5000);
        cancel(as("joan"), id(joan), 200); dispatch();
        assertThat(eventsOf("SeatReleased")).extracting(e -> e.get("payload", Document.class).getBoolean("notifyWaitlist")).containsExactlyInAnyOrder(true, false);
        assertThat(entry(duna)).containsEntry("state", "ACTIVE");
        assertThat(code(holdFor(as("laura"), "last", "s08-d-duna", duna, 422))).isEqualTo("WAITLIST_NOT_NOTIFIED");
        assertThat(code(claim(as("laura"), duna, "s08-no-hold", null, 422))).as("the same code as the hold").isEqualTo("WAITLIST_NOT_NOTIFIED");
    }
}
