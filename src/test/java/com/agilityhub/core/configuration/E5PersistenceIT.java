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
        for (String collection : List.of("bookings", "seat_holds", "waitlist_entries", "seat_locks", "training_bookings", "weeks", "class_sessions")) {
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
                    now.plusSeconds(1800), null, null, null, starts, "2026-10-04", 1L, now, "account-a", now, "account-a"));
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
}
