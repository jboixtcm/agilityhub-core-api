package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.scheduling.application.ports.AttendanceStatusPort.AttendanceStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The S10 helper behind `GET /instructor/day` and, through the port, the S06 calendar (E6-T01 step 7). */
class AttendanceStatusCalculatorTest {
    private final BookingContext context = mock(BookingContext.class);
    private final AttendanceStatusCalculator calculator = new AttendanceStatusCalculator(context);

    @Test void T_10_09_statusUsesTheClubZoneAndAttendanceEditDays() {
        when(context.zone()).thenReturn(ZoneId.of("Europe/Madrid"));
        when(context.integer("attendance.editDays")).thenReturn(1);
        var monday = LocalDate.of(2026, 8, 3);
        assertThat(calculator.window(monday).editableUntil()).isEqualTo(Instant.parse("2026-08-04T21:59:59Z"));
        when(context.now()).thenReturn(Instant.parse("2026-08-03T06:25:00Z"));
        assertThat(calculator.status(monday, "ACTIVE", 1, 4, 0, 0)).isEqualTo(AttendanceStatus.PENDING);
        assertThat(calculator.status(monday, "ACTIVE", 4, 4, 0, 0)).isEqualTo(AttendanceStatus.DONE);
        when(context.now()).thenReturn(Instant.parse("2026-08-04T22:00:00Z"));
        assertThat(calculator.status(monday, "FINISHED", 1, 4, 0, 0)).isEqualTo(AttendanceStatus.CLOSED);
        when(context.integer("attendance.editDays")).thenReturn(2);
        assertThat(calculator.status(monday, "FINISHED", 1, 4, 0, 0)).isEqualTo(AttendanceStatus.PENDING);
        when(context.now()).thenReturn(Instant.parse("2026-08-02T21:59:59Z"));
        assertThat(calculator.status(monday, "ACTIVE", 0, 4, 0, 0)).isEqualTo(AttendanceStatus.NONE);
    }

    /** E6-T01 round 2: a NOTIFIED row after the class end keeps its booking ACTIVE (R-10-05); the total counts it once (R-10-02). */
    @Test void T_10_09_totalCountsEveryNotifiedRowOnce() {
        when(context.zone()).thenReturn(ZoneId.of("Europe/Madrid"));
        when(context.integer("attendance.editDays")).thenReturn(1);
        when(context.now()).thenReturn(Instant.parse("2026-08-03T18:00:00Z"));
        var monday = LocalDate.of(2026, 8, 3);
        // After the end: one booking, still ACTIVE, marked NOTIFIED -> every row is marked.
        assertThat(AttendanceStatusCalculator.total(1, 1, 1)).isEqualTo(1);
        assertThat(calculator.status(monday, "FINISHED", 1, 1, 1, 1)).isEqualTo(AttendanceStatus.DONE);
        // In time: the NOTIFIED booking released its seat, so it is not in `booked` any more.
        assertThat(AttendanceStatusCalculator.total(1, 1, 0)).isEqualTo(2);
        assertThat(calculator.status(monday, "FINISHED", 2, 1, 1, 0)).isEqualTo(AttendanceStatus.DONE);
        assertThat(calculator.status(monday, "FINISHED", 1, 1, 1, 0)).isEqualTo(AttendanceStatus.PENDING);
        // Both kinds on one sheet: 2 live bookings (one of them NOTIFIED after the end) + 1 in-time NOTIFIED = 3 rows.
        assertThat(AttendanceStatusCalculator.total(2, 2, 1)).isEqualTo(3);
        assertThat(calculator.status(monday, "FINISHED", 2, 2, 2, 1)).isEqualTo(AttendanceStatus.PENDING);
        assertThat(calculator.status(monday, "FINISHED", 3, 2, 2, 1)).isEqualTo(AttendanceStatus.DONE);
    }
}
