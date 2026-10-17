package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.clubs.bookings.domain.AttendanceMetrics.ClassRecord;
import com.agilityhub.core.clubs.bookings.domain.AttendanceMetrics.LastClass;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link AttendanceMetrics} (S10 R-10-09; T-10-05): two last classes starting at the same
 * instant come in booking-id order, whatever order they were read in.
 */
class AttendanceMetricsSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T16:00:00Z");

    @Test void T_10_05_lastClassesStartingAtTheSameInstantAreOrderedByBookingId() {
        // Duna in two classes of the same start (two rings), read newest id first; plus an older class.
        var start = NOW.minusSeconds(86_400);
        var records = List.of(
                new ClassRecord("booking-b", start, BookingState.ACTIVE, AttendanceState.PRESENT, false),
                new ClassRecord("booking-a", start, BookingState.ACTIVE, AttendanceState.NO_SHOW, false),
                new ClassRecord("booking-c", start.minusSeconds(86_400), BookingState.ACTIVE, AttendanceState.PRESENT, false));

        var last = AttendanceMetrics.lastClasses(records, NOW);

        assertThat(last).extracting(l -> l.record().bookingId()).containsExactly("booking-a", "booking-b", "booking-c");
        assertThat(last).extracting(LastClass::display)
                .containsExactly(AttendanceMetrics.Display.NO_SHOW, AttendanceMetrics.Display.PRESENT, AttendanceMetrics.Display.PRESENT);
    }
}
