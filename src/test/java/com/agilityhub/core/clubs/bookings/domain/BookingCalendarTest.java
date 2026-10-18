package com.agilityhub.core.clubs.bookings.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S08 R-08-08 `.ics` body and RFC 5545 `STATUS` / `SEQUENCE` / `DTSTAMP` (INC-40, E11-T10). */
class BookingCalendarTest {
    static final Instant STARTS = Instant.parse("2026-10-15T16:50:00Z"), ENDS = STARTS.plusSeconds(3600), NOW = Instant.parse("2026-10-09T07:30:00Z");

    /** R-08-08 — INC-40 (E11-T10): the event carries its SEQUENCE and STATUS next to a stable UID and a DTSTAMP of when it was served. */
    @Test void T_08_15_INC_40_theIcsCarriesSequenceStatusAndTheStampItWasServedAt() {
        var confirmed = BookingCalendar.ics(new BookingCalendar.Event("b1@club.agilityhub", "B+C · Duna", "Central", STARTS, ENDS, NOW, 3, BookingCalendar.Status.CONFIRMED));
        assertThat(confirmed.split("\r\n")).containsSubsequence("BEGIN:VEVENT", "UID:b1@club.agilityhub", "DTSTAMP:20261009T073000Z", "SEQUENCE:3", "STATUS:CONFIRMED",
                "DTSTART:20261015T165000Z", "DTEND:20261015T175000Z", "END:VEVENT");
        var cancelled = BookingCalendar.ics(new BookingCalendar.Event("b1@club.agilityhub", "B+C · Duna", "Central", STARTS, ENDS, NOW, 4, BookingCalendar.Status.CANCELLED));
        assertThat(cancelled).contains("UID:b1@club.agilityhub", "SEQUENCE:4", "STATUS:CANCELLED").doesNotContain("STATUS:CONFIRMED");
        // The links' event (no sequence) is a confirmed one with SEQUENCE 0.
        assertThat(BookingCalendar.ics(new BookingCalendar.Event("b1@club.agilityhub", "B+C", "Central", STARTS, ENDS, NOW))).contains("SEQUENCE:0", "STATUS:CONFIRMED");
    }

    /** R-08-08, R-08-18, R-08-21 — INC-40 (E11-T10): every cancelled booking state and a cancelled class are CANCELLED; PAYMENT_PENDING is TENTATIVE. */
    @Test void T_08_15_INC_40_theStatusFollowsTheBookingAndItsClass() {
        assertThat(BookingCalendar.status(BookingState.ACTIVE, false)).isEqualTo(BookingCalendar.Status.CONFIRMED);
        assertThat(BookingCalendar.status(BookingState.PAYMENT_PENDING, false)).isEqualTo(BookingCalendar.Status.TENTATIVE);
        for (var state : new BookingState[] {BookingState.CANCELLED, BookingState.CANCELLED_LATE, BookingState.CANCELLED_BY_CLUB}) {
            assertThat(BookingCalendar.status(state, false)).as(state.name()).isEqualTo(BookingCalendar.Status.CANCELLED);
        }
        assertThat(BookingCalendar.status(BookingState.ACTIVE, true)).isEqualTo(BookingCalendar.Status.CANCELLED);
        assertThat(BookingCalendar.status(BookingState.PAYMENT_PENDING, true)).isEqualTo(BookingCalendar.Status.CANCELLED);
    }
}
