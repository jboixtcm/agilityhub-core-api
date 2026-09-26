package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.domain.WeekState;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.clubs.scheduling.persistence.Week;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;

/** E5-T01 step 6: S08/S09/S15 documents, mandatory indexes (MODEL_DADES_PLATAFORMA §7) and the S15 §3 marks on existing aggregates. */
class E5PersistenceIT extends AbstractIntegrationTest {
    static final String CLUB = "e5-persistence-a", OTHER = "e5-persistence-b";
    @Autowired MongoTemplate mongo;
    @Autowired BookingRepository bookings;
    @Autowired SeatHoldRepository holds;
    @Autowired WaitlistEntryRepository waitlist;
    @Autowired SeatLockRepository seatLocks;
    @Autowired TrainingBookingRepository training;

    @BeforeEach void clean() {
        for (String collection : List.of("bookings", "seat_holds", "waitlist_entries", "seat_locks", "training_bookings", "weeks", "class_sessions", "ring_slot_locks")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
    }
    private Map<String, Document> indexes(String collection) {
        var result = new LinkedHashMap<String, Document>();
        for (Document index : mongo.getCollection(collection).listIndexes()) { result.put(index.getString("name"), index); }
        return result;
    }
    private static Document keys(String... fields) {
        var keys = new Document();
        for (String field : fields) { keys.append(field, 1); }
        return keys;
    }

    @Test void T_08_29_T_09_32_T_15_03_collectionsDeclareTheirIndexesTtlAndUniqueGuards() {
        var booking = indexes("bookings");
        assertThat(booking.get("booking_club_class_dog_state").get("key")).isEqualTo(keys("clubId", "classSessionId", "dogId", "state"));
        assertThat(booking.get("booking_club_dog_week_state").get("key")).isEqualTo(keys("clubId", "dogId", "bookingWeekKey", "state"));
        assertThat(booking.get("booking_club_member_week_state").get("key")).isEqualTo(keys("clubId", "memberId", "bookingWeekKey", "state"));
        assertThat(booking.get("booking_club_state_starts").get("key")).isEqualTo(keys("clubId", "state", "classStartsAt"));
        var hold = indexes("seat_holds");
        assertThat(hold.get("seat_hold_ttl").get("expireAfterSeconds", Number.class).intValue()).isZero();
        assertThat(hold.get("seat_hold_club_class_dog").getBoolean("unique")).isTrue();
        var entries = indexes("waitlist_entries");
        assertThat(entries.get("waitlist_club_class_state").get("key")).isEqualTo(keys("clubId", "classSessionId", "state"));
        assertThat(entries.get("waitlist_club_dog_week_state").get("key")).isEqualTo(keys("clubId", "dogId", "bookingWeekKey", "state"));
        assertThat(entries.get("waitlist_club_state_confirm").get("key")).isEqualTo(keys("clubId", "state", "confirmBy"));
        var trainings = indexes("training_bookings");
        var seat = trainings.get("training_active_seat");
        assertThat(seat.get("key")).isEqualTo(keys("clubId", "ringId", "startsAt", "seatIndex"));
        assertThat(seat.getBoolean("unique")).isTrue();
        assertThat(seat.get("partialFilterExpression", Document.class)).isEqualTo(new Document("state", "ACTIVE"));
        assertThat(trainings).containsKeys("training_club_starts", "training_club_dog_week_state", "training_club_member_week_state", "training_club_state_starts");
        var runs = indexes("job_runs");
        assertThat(runs.get("job_run_occurrence").get("key")).isEqualTo(keys("clubId", "job", "scheduledFor", "trigger"));
        assertThat(runs.get("job_run_occurrence").getBoolean("unique")).isTrue();
        assertThat(runs).containsKeys("job_run_club_job_started", "job_run_finished");
        assertThat(indexes("job_locks").get("job_lock_ttl").get("expireAfterSeconds", Number.class).intValue()).isZero();
    }

    @Test void T_09_32_theActiveSeatGuardRefusesASecondActiveBookingButNotACancelledOne() {
        Instant now = clock.instant(), slot = Instant.parse("2026-10-06T06:30:00Z");
        mongo.insert(trainingBooking("e5p-t1", TrainingBookingState.ACTIVE, slot, now));
        assertThatThrownBy(() -> mongo.insert(trainingBooking("e5p-t2", TrainingBookingState.ACTIVE, slot, now))).isInstanceOf(DuplicateKeyException.class);
        mongo.insert(trainingBooking("e5p-t3", TrainingBookingState.CANCELLED, slot, now));
        mongo.insert(trainingBooking("e5p-t4", TrainingBookingState.CANCELLED, slot, now));
        try (var scope = TenantContext.open(CLUB)) {
            assertThat(training.findById("e5p-t1")).get().extracting(TrainingBooking::seatIndex, TrainingBooking::slotId).containsExactly(0, "ring-a_2026-10-06T06:30:00Z");
            assertThat(training.findAll()).hasSize(3);
        }
        try (var scope = TenantContext.open(OTHER)) { assertThat(training.findById("e5p-t1")).isEmpty(); }
    }
    private TrainingBooking trainingBooking(String id, TrainingBookingState state, Instant slot, Instant now) {
        return new TrainingBooking(id, CLUB, "member-a", "dog-" + id, "ring-a", slot, slot.plusSeconds(1800), "ring-a_2026-10-06T06:30:00Z", 0,
                Instant.parse("2026-10-04T18:00:00Z"), state, TrainingOrigin.APP, "account-a", null, null, null, null, null, null, null, 1L, now, now, "account-a");
    }

    @Test void T_08_29_bookingsHoldsEntriesAndLocksRoundTripWithinTheTenant() {
        Instant now = clock.instant(), starts = Instant.parse("2026-10-07T16:50:00Z");
        try (var scope = TenantContext.open(CLUB)) {
            bookings.insert(new Booking("e5p-b1", CLUB, "e5p-class-a", "dog-a", "member-a", BookingState.ACTIVE, BookingOrigin.BACKOFFICE, now,
                    new Booking.Actor("account-admin", "member-a", "Example Admin"), starts, starts.plusSeconds(3600), "2026-10-04",
                    now, new Booking.Canceller("account-a", ActorRole.MEMBER, "Example", null), BookingCancelReason.SWAP, "Example message", false, 150,
                    "b0", "b2", "entry-a", "movement-a", "movement-b",
                    new Booking.Charge(ChargeMode.CHARGE_ON_ATTENDANCE, new Money(1200, "EUR"), null, null, null, null, null), now,
                    1L, now, "account-a", now, "account-a"));
            var stored = bookings.findById("e5p-b1").orElseThrow();
            assertThat(stored.charge().price()).isEqualTo(new Money(1200, "EUR"));
            assertThat(stored.bookedBy().impersonatedMemberId()).isEqualTo("member-a");
            assertThat(stored.cancelledBy().role()).isEqualTo(ActorRole.MEMBER);
            assertThat(stored.reminderSentAt()).isEqualTo(now);
            holds.insert(new SeatHold("e5p-h1", CLUB, "e5p-class-a", "dog-a", "member-a", "account-a", null, now, now.plusSeconds(30)));
            assertThatThrownBy(() -> holds.insert(new SeatHold("e5p-h2", CLUB, "e5p-class-a", "dog-a", "member-a", "account-a", null, now, now.plusSeconds(30))))
                    .isInstanceOf(DuplicateKeyException.class);
            waitlist.insert(new WaitlistEntry("e5p-w1", CLUB, "e5p-class-a", "dog-b", "member-a", "account-a", now, WaitlistState.NOTIFIED, 1, now,
                    now.plusSeconds(1800), null, null, null, null, starts, "2026-10-04", 1L, now, "account-a", now, "account-a"));
            assertThat(waitlist.findById("e5p-w1").orElseThrow().confirmBy()).isEqualTo(now.plusSeconds(1800));
            seatLocks.insert(new SeatLock("e5p-class-a", CLUB, 0));
            assertThat(seatLocks.findById("e5p-class-a").orElseThrow().version()).isZero();
            assertThatThrownBy(() -> bookings.insert(new Booking("e5p-b9", OTHER, "e5p-class-a", "dog-a", "member-a", BookingState.ACTIVE, BookingOrigin.APP, now,
                    null, starts, starts, "2026-10-04", null, null, null, null, null, null, null, null, null, null, null, null, null, 1L, now, null, now, null)))
                    .isInstanceOf(ApiException.class);
        }
        try (var scope = TenantContext.open(OTHER)) {
            assertThat(bookings.findById("e5p-b1")).isEmpty(); assertThat(holds.findAll()).isEmpty(); assertThat(waitlist.findAll()).isEmpty();
        }
    }

    @Test void T_15_11_T_15_12b_weekAndClassSessionCarryTheS15Marks() {
        Instant now = clock.instant();
        mongo.insert(new Week("e5p-week-a", CLUB, 2026, 42, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18), WeekState.VALIDATED, null, null,
                null, null, now, "account-a", 1L, now, "account-a", now, "account-a", Instant.parse("2026-10-04T18:00:00Z"), now));
        var week = mongo.findById("e5p-week-a", Week.class);
        assertThat(week.openedAt()).isEqualTo(Instant.parse("2026-10-04T18:00:00Z"));
        assertThat(week.openingNotifiedAt()).isEqualTo(now);
        var legacy = new Week("e5p-week-b", CLUB, 2026, 43, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), WeekState.PENDING, null, null,
                null, null, null, null, 1L, now, "account-a", now, "account-a");
        assertThat(legacy.openedAt()).isNull();
        var session = new ClassSession("e5p-class-a", CLUB, "e5p-week-a", LocalDate.of(2026, 10, 12), "18:50", "19:50", Instant.parse("2026-10-12T16:50:00Z"),
                Instant.parse("2026-10-12T17:50:00Z"), null, List.of(), List.of(), 5, com.agilityhub.core.clubs.scheduling.domain.CapacityMode.AUTO, null,
                com.agilityhub.core.clubs.scheduling.domain.ClassState.FINISHED, new ClassSession.Counters(1, 0),
                new ClassSession.Risk(false, List.of("e5p-b1"), now, now), null, null, null, null, 1L, now, "account-a", now, "account-a", now);
        mongo.insert(session);
        var stored = mongo.findById("e5p-class-a", ClassSession.class);
        assertThat(stored.finishedAt()).isEqualTo(now);
        assertThat(stored.risk().lowAlertSentAt()).isEqualTo(now);
        assertThat(stored.risk().notifiedBookingIds()).containsExactly("e5p-b1");
        assertThat(stored.risk().adminNotifiedAt()).isEqualTo(now);
    }

    @Autowired @org.springframework.beans.factory.annotation.Qualifier("schedulingCollections") org.springframework.boot.ApplicationRunner schedulingCollections;
    @Autowired com.agilityhub.core.clubs.scheduling.persistence.RingSlotLockRepository ringSlots;
    private void startScheduling() throws Exception { schedulingCollections.run(new org.springframework.boot.DefaultApplicationArguments()); }

    /**
     * E5-T17 (S15 R-15-19 and `MODEL_DADES_PLATAFORMA.md` `ring_slot_locks`, amended 25-09): the startup runner gives
     * `ring_slot_locks` a TTL index on `expiresAt` (`expireAfterSeconds: 0`), idempotent on restart and on two instances
     * starting together; every touch of a slot sets `expiresAt` = `startsAt` + 7 days, and a later write recreates a
     * document the TTL removed with the same upsert. E5-T18 (review E5-T17 #1): T-15-27 is P9's fixture scenario, so the
     * test is named after the rule it asserts.
     */
    @Test void R_15_19_ringSlotLocksExpireSevenDaysAfterTheirSlotThroughATtlIndex() throws Exception {
        var ttl = indexes("ring_slot_locks").get("ring_slot_lock_ttl");
        assertThat(ttl).as("created at startup").isNotNull();
        assertThat(ttl.get("key")).isEqualTo(keys("expiresAt"));
        assertThat(ttl.get("expireAfterSeconds", Number.class).intValue()).isZero();
        // A restart finds it in place; two instances starting together on a collection without it both start.
        startScheduling();
        mongo.indexOps("ring_slot_locks").dropIndex("ring_slot_lock_ttl");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var starts = List.of(pool.submit(() -> { startScheduling(); return null; }), pool.submit(() -> { startScheduling(); return null; }));
            for (var start : starts) { start.get(60, java.util.concurrent.TimeUnit.SECONDS); }
        } finally { pool.shutdownNow(); }
        assertThat(indexes("ring_slot_locks")).containsKey("ring_slot_lock_ttl");
        assertThat(indexes("ring_slot_locks").get("ring_slot_lock_ttl").get("expireAfterSeconds", Number.class).intValue()).isZero();

        var startsAt = Instant.parse("2026-10-06T16:00:00Z"); String id = CLUB + ":e5p-ring:" + startsAt;
        try (var tenant = TenantContext.open(CLUB)) { ringSlots.touch("e5p-ring", startsAt); ringSlots.touch("e5p-ring", startsAt); }
        var lock = mongo.findById(id, Document.class, "ring_slot_locks");
        assertThat(lock.getString("clubId")).isEqualTo(CLUB);
        assertThat(lock.getDate("expiresAt").toInstant()).isEqualTo(Instant.parse("2026-10-13T16:00:00Z"));
        assertThat(lock.get("sequence", Number.class).longValue()).isEqualTo(2);
        // The TTL monitor removed it (the test container runs without it: removed by hand); a later write recreates it.
        mongo.remove(Query.query(Criteria.where("_id").is(id)), "ring_slot_locks");
        try (var tenant = TenantContext.open(CLUB)) { ringSlots.touch("e5p-ring", startsAt); }
        lock = mongo.findById(id, Document.class, "ring_slot_locks");
        assertThat(lock.getDate("expiresAt").toInstant()).isEqualTo(Instant.parse("2026-10-13T16:00:00Z"));
        assertThat(lock.get("sequence", Number.class).longValue()).isEqualTo(1);
    }

    /**
     * E5-T18 step 3 (review E5-T17 #3, S15 R-15-19): the one-off backfill of `docs/DEPLOY.md` gives a document written before
     * E5-T17 its `expiresAt` = `startsAt` + 7 days, leaves a current one as it is, and a second run changes nothing. E5-T21
     * (review E5-T18 #5): the filter and pipeline are read from the `--eval` expression written there, so a drift of the
     * document fails this test.
     */
    @Test void R_15_19_theDeployBackfillGivesTheOldRingSlotLocksTheirExpiry() throws Exception {
        var startsAt = Instant.parse("2026-09-01T17:00:00Z");
        var locks = mongo.getCollection("ring_slot_locks");
        locks.insertOne(new Document("_id", CLUB + ":e5p-old:" + startsAt).append("clubId", CLUB).append("ringId", "e5p-old")
                .append("startsAt", Date.from(startsAt)).append("sequence", 4));
        try (var tenant = TenantContext.open(CLUB)) { ringSlots.touch("e5p-new", startsAt); }
        var arguments = deployBackfill();
        var filter = (Document) arguments.get(0); @SuppressWarnings("unchecked") var backfill = (List<Document>) arguments.get(1);
        assertThat(filter).isEqualTo(new Document("expiresAt", new Document("$exists", false)));
        assertThat(locks.updateMany(filter, backfill).getModifiedCount()).as("the one document written before E5-T17").isEqualTo(1);
        var old = locks.find(new Document("_id", CLUB + ":e5p-old:" + startsAt)).first();
        assertThat(old.getDate("expiresAt").toInstant()).isEqualTo(Instant.parse("2026-09-08T17:00:00Z"));
        assertThat(old.get("sequence", Number.class).longValue()).isEqualTo(4);
        assertThat(locks.find(new Document("_id", CLUB + ":e5p-new:" + startsAt)).first().getDate("expiresAt").toInstant()).isEqualTo(Instant.parse("2026-09-08T17:00:00Z"));
        assertThat(locks.updateMany(filter, backfill).getModifiedCount()).as("a second run").isZero();
    }
    /** The arguments (filter, pipeline) of the one `db.ring_slot_locks.updateMany(…)` that `docs/DEPLOY.md` runs with `mongosh --eval`. */
    private static List<Object> deployBackfill() throws Exception {
        String call = "db.ring_slot_locks.updateMany(";
        var commands = java.nio.file.Files.readAllLines(java.nio.file.Path.of("docs/DEPLOY.md")).stream().filter(line -> line.contains("--eval '" + call)).toList();
        assertThat(commands).as("one backfill command in DEPLOY.md").hasSize(1);
        String command = commands.getFirst();
        String expression = command.substring(command.indexOf("--eval '") + "--eval '".length(), command.lastIndexOf('\''));
        assertThat(expression).startsWith(call).endsWith(")");
        var arguments = Document.parse("{arguments: [" + expression.substring(call.length(), expression.length() - 1) + "]}").getList("arguments", Object.class);
        assertThat(arguments).hasSize(2);
        return arguments;
    }

    /**
     * E5-T18 (review E5-T17 #4, S15 R-15-19), on the real database: an index that already carries the TTL's name and a 0 s
     * TTL but another key (`{startsAt: 1}`) makes Mongo refuse the runner's `ensureIndex`, and the tolerance check then
     * compares the key too, so the start fails instead of leaving the locks without a TTL. The index is restored after.
     */
    @Test void R_15_19_anotherIndexUnderTheTtlNameStaysAStartupFailure() throws Exception {
        var locks = mongo.getCollection("ring_slot_locks");
        locks.dropIndex("ring_slot_lock_ttl");
        try {
            locks.createIndex(keys("startsAt"), new com.mongodb.client.model.IndexOptions().name("ring_slot_lock_ttl").expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
            assertThatThrownBy(this::startScheduling).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(indexes("ring_slot_locks").get("ring_slot_lock_ttl").get("key")).as("the other index is left as it is").isEqualTo(keys("startsAt"));
        } finally {
            locks.dropIndex("ring_slot_lock_ttl");
            startScheduling();
        }
        assertThat(indexes("ring_slot_locks").get("ring_slot_lock_ttl").get("key")).isEqualTo(keys("expiresAt"));
    }
}
