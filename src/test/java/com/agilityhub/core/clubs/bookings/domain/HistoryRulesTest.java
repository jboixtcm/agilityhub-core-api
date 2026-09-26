package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.clubs.bookings.domain.HistoryRules.DetailKind;
import com.agilityhub.core.clubs.bookings.domain.HistoryRules.Entry;
import com.agilityhub.core.clubs.bookings.domain.HistoryRules.State;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

/** S10 R-10-14 class rows of screen 25 for every S08 `cancelReason` × `cancelledBy` (no Spring, no Mongo). */
class HistoryRulesTest {
    static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    static final Instant PAST = NOW.minusSeconds(86_400 * 3), FUTURE = NOW.plusSeconds(86_400 * 3), CANCELLED_AT = Instant.parse("2026-07-21T17:10:00Z");

    static HistoryRules.ClassBooking booking(BookingState state, BookingCancelReason reason, boolean impersonated, Instant starts) {
        return new HistoryRules.ClassBooking(state, reason, impersonated, starts, state == BookingState.ACTIVE ? null : CANCELLED_AT, AttendanceState.PENDING, false, null);
    }

    /** Every cancellation, in the past and in the future (§13-14: cancelled ones leave 03 at once). */
    @ParameterizedTest(name = "{0} {1} impersonated={2} → {3} counts={4} {5}")
    @CsvSource({
            "CANCELLED,      MEMBER,               false, CANCELLED,         false, BY_MEMBER_IN_TIME",
            "CANCELLED_LATE, MEMBER,               false, CANCELLED_LATE,    true,  BY_MEMBER",
            "CANCELLED,      INSTRUCTOR_NOTICE,    false, CANCELLED,         false, INSTRUCTOR_NOTICE_IN_TIME",
            "CANCELLED_LATE, INSTRUCTOR_NOTICE,    false, CANCELLED_LATE,    true,  INSTRUCTOR_NOTICE",
            "CANCELLED,      SWAP,                 false, CANCELLED,         false, BY_MEMBER_IN_TIME",
            "CANCELLED,      MEMBER,               true,  CANCELLED,         false, BY_CLUB_ON_BEHALF",
            "CANCELLED_LATE, MEMBER,               true,  CANCELLED_LATE,    true,  BY_CLUB_ON_BEHALF",
            "CANCELLED,      SWAP,                 true,  CANCELLED,         false, BY_CLUB_ON_BEHALF",
            "CANCELLED,      INACTIVITY,           false, CANCELLED,         false, SYSTEM",
            "CANCELLED,      LEAVE,                false, CANCELLED,         false, SYSTEM",
            "CANCELLED,      PAYMENT_TIMEOUT,      false, CANCELLED,         false, SYSTEM",
            "CANCELLED_BY_CLUB, CLUB_CLASS_CANCELLED, false, CANCELLED_BY_CLUB, false, BY_CLUB",
            "CANCELLED_BY_CLUB, AUTO_CANCELLED,    false, CANCELLED_BY_CLUB, false, BY_CLUB"})
    void T_10_07_everyCancelReasonAndRoleMapsToTheScreen25Row(BookingState booking, BookingCancelReason reason, boolean impersonated, State state, boolean counts,
            DetailKind kind) {
        for (var starts : new Instant[] {PAST, FUTURE}) {
            var entry = HistoryRules.classEntry(booking(booking, reason, impersonated, starts), NOW).orElseThrow();
            assertThat(entry.state()).isEqualTo(state);
            assertThat(entry.counts()).isEqualTo(counts);
            assertThat(entry.kind()).isEqualTo(kind);
            // The sentences with «el {dd/mm} a les {hh:mm}» carry the cancellation instant.
            assertThat(entry.at()).isEqualTo(booking == BookingState.CANCELLED_LATE ? CANCELLED_AT : null);
        }
    }

    @Test void T_10_07_activeBookingsAppearOnceStartedAndLiveOnesNever() {
        assertThat(HistoryRules.classEntry(booking(BookingState.ACTIVE, null, false, FUTURE), NOW)).isEmpty();
        assertThat(HistoryRules.classEntry(booking(BookingState.PAYMENT_PENDING, null, false, PAST), NOW)).isEmpty();
        assertThat(HistoryRules.classEntry(booking(BookingState.ACTIVE, null, false, PAST), NOW)).contains(new Entry(State.DONE, true, null, null));
        assertThat(HistoryRules.classEntry(booking(BookingState.ACTIVE, null, false, NOW), NOW)).contains(new Entry(State.DONE, true, null, null));
        var present = new HistoryRules.ClassBooking(BookingState.ACTIVE, null, false, PAST, null, AttendanceState.PRESENT, false, null);
        assertThat(HistoryRules.classEntry(present, NOW)).contains(new Entry(State.DONE, true, null, null));
        var noShow = new HistoryRules.ClassBooking(BookingState.ACTIVE, null, false, PAST, null, AttendanceState.NO_SHOW, false, null);
        assertThat(HistoryRules.classEntry(noShow, NOW)).contains(new Entry(State.NO_SHOW, true, DetailKind.NO_SHOW, null));
        // «Ha avisat» after the class end: the booking stayed ACTIVE, it counts, and the row says the member told the club (assumption).
        var noticeAt = PAST.plusSeconds(7200);
        var afterEnd = new HistoryRules.ClassBooking(BookingState.ACTIVE, null, false, PAST, null, AttendanceState.NOTIFIED, true, noticeAt);
        assertThat(HistoryRules.classEntry(afterEnd, NOW)).contains(new Entry(State.CANCELLED_LATE, true, DetailKind.INSTRUCTOR_NOTICE, noticeAt));
        // A cancellation stored without a reason (older data) reads as the member's.
        assertThat(HistoryRules.classEntry(booking(BookingState.CANCELLED, null, false, PAST), NOW).orElseThrow().kind()).isEqualTo(DetailKind.BY_MEMBER_IN_TIME);
    }
}
