package com.agilityhub.core.clubs.bookings.domain;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S08 R-08-12/13/14 waiting-list rules, pure (no Spring, no Mongo, fixed instants). */
class WaitlistRulesTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = LocalDateTime.parse("2026-10-06T10:00").atZone(MADRID).toInstant(); // Tuesday, W0 = [Sun 04-10 20:00, Sun 11-10 20:00)
    static Instant local(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }
    static BookingLimits.Counted booking(String id, BookingState state, String starts) {
        return new BookingLimits.Counted(id, "class-" + id, "duna", "laura", state, local(starts));
    }
    static BookingLimits.Result limit(BookingLimits.Counted... bookings) {
        return BookingLimits.evaluate(List.of(bookings), LimitUnit.DOG, "duna", "laura", 2, NOW, Duration.ofMinutes(240));
    }
    static WaitlistRules.Join join(int ofClass, int ofDog, BookingLimits.Result limit) { return new WaitlistRules.Join(ofClass, 3, ofDog, 2, 1, limit); }

    @Test void T_08_08_waitingListLimitsPerClassPerDogWeekAttendedAndAcceptance() {
        var free = limit();
        // 3 per class (waitlist.maxPerClass): the 4th waits for nothing → CLASS («Completa · ⏳3/3»).
        assertThat(WaitlistRules.join(join(2, 0, free))).isEmpty();
        assertThat(WaitlistRules.join(join(3, 0, free))).contains(WaitlistRules.Rejection.CLASS);
        // 2 per dog and booking week (waitlist.maxPerDogPerWeek).
        assertThat(WaitlistRules.join(join(0, 1, free))).isEmpty();
        assertThat(WaitlistRules.join(join(0, 2, free))).contains(WaitlistRules.Rejection.DOG_WEEK);
        // «Ja ha fet classe» (a counted booking already started or CANCELLED_LATE) → 1 (waitlist.maxPerDogPerWeekIfAttended).
        var done = limit(booking("mon", BookingState.ACTIVE, "2026-10-05T18:50"));
        assertThat(WaitlistRules.attended(done)).isTrue();
        assertThat(WaitlistRules.join(join(0, 0, done))).isEmpty();
        assertThat(WaitlistRules.join(join(0, 1, done))).as("S08 example: Duna, 1 class done + 1 entry").contains(WaitlistRules.Rejection.DOG_WEEK);
        var late = limit(booking("wed", BookingState.CANCELLED_LATE, "2026-10-07T18:50"));
        assertThat(WaitlistRules.attended(late)).isTrue();
        assertThat(WaitlistRules.join(join(0, 1, late))).contains(WaitlistRules.Rejection.DOG_WEEK);
        // A future ACTIVE booking or an in-time cancellation is not «attended».
        var future = limit(booking("fri", BookingState.ACTIVE, "2026-10-09T20:00"), booking("thu", BookingState.CANCELLED, "2026-10-08T18:50"));
        assertThat(WaitlistRules.attended(future)).isFalse();
        assertThat(WaitlistRules.join(join(0, 1, future))).isEmpty();
        // Acceptance: at the limit with a swappable booking the entry is taken; with none the dog could never take the seat.
        var swappable = limit(booking("mon", BookingState.ACTIVE, "2026-10-05T18:50"), booking("fri", BookingState.ACTIVE, "2026-10-09T20:00"));
        assertThat(swappable.reached()).isTrue(); assertThat(swappable.swappable()).hasSize(1);
        assertThat(WaitlistRules.join(join(0, 0, swappable))).isEmpty();
        var stuck = limit(booking("mon", BookingState.ACTIVE, "2026-10-05T18:50"), booking("tue", BookingState.ACTIVE, "2026-10-06T12:00"));
        assertThat(stuck.done()).isTrue();
        assertThat(WaitlistRules.join(join(0, 0, stuck))).contains(WaitlistRules.Rejection.BOOKING_LIMIT);
        // Order of the checks: class first, then the dog's week, then acceptance.
        assertThat(WaitlistRules.join(join(3, 2, stuck))).contains(WaitlistRules.Rejection.CLASS);
        assertThat(WaitlistRules.join(join(0, 1, stuck))).contains(WaitlistRules.Rejection.DOG_WEEK);
    }

    @Test void T_08_20_T_08_21_T_08_45_offersPerModeNeverRepeatAnOpenOffer() {
        // ALL_AT_ONCE: every ACTIVE entry while one seat is free; never with no free seat.
        assertThat(WaitlistRules.toNotify(WaitlistMode.ALL_AT_ONCE, 1, 0, 3)).isEqualTo(3);
        assertThat(WaitlistRules.toNotify(WaitlistMode.ALL_AT_ONCE, 1, 3, 0)).as("already NOTIFIED are not told again").isZero();
        assertThat(WaitlistRules.toNotify(WaitlistMode.ALL_AT_ONCE, 0, 0, 3)).isZero();
        // FIFO: as many open offers as free seats.
        assertThat(WaitlistRules.toNotify(WaitlistMode.FIFO, 1, 0, 3)).isEqualTo(1);
        assertThat(WaitlistRules.toNotify(WaitlistMode.FIFO, 2, 0, 3)).as("T-08-45: two seats at once").isEqualTo(2);
        assertThat(WaitlistRules.toNotify(WaitlistMode.FIFO, 2, 1, 3)).isEqualTo(1);
        assertThat(WaitlistRules.toNotify(WaitlistMode.FIFO, 1, 1, 2)).as("T-08-34: a second delivery offers nothing").isZero();
        assertThat(WaitlistRules.toNotify(WaitlistMode.FIFO, 3, 0, 2)).as("never more than the queue").isEqualTo(2);
        assertThat(WaitlistRules.toNotify(WaitlistMode.FIFO, -1, 0, 2)).isZero();
    }

    @Test void T_08_21_fifoConfirmByIsTheShorterOfTheWindowAndTheClassStart() {
        var start = local("2026-10-08T20:00");
        assertThat(WaitlistRules.confirmBy(local("2026-10-08T15:00"), 30, start)).isEqualTo(local("2026-10-08T15:30"));
        assertThat(WaitlistRules.confirmBy(local("2026-10-08T19:15"), 60, start)).as("min(now + window, classStartsAt)").isEqualTo(start);
        assertThat(WaitlistRules.confirmBy(local("2026-10-08T19:00"), 60, start)).isEqualTo(start);
        // An offer needs strictly more than waitlist.notifyThresholdMinutes left (30 exactly → no offer).
        assertThat(WaitlistRules.inTime(local("2026-10-08T19:29:59"), start, 30)).isTrue();
        assertThat(WaitlistRules.inTime(local("2026-10-08T19:30"), start, 30)).isFalse();
        assertThat(WaitlistRules.inTime(local("2026-10-08T19:45"), start, 30)).isFalse();
    }
}
