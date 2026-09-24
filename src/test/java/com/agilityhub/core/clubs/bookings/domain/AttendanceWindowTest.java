package com.agilityhub.core.clubs.bookings.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S10 R-10-03 window (the contract half of T-10-01) and the `attendance.status` of S10 §6. */
class AttendanceWindowTest {
    static final LocalDate MONDAY = LocalDate.of(2026, 8, 3);

    @Test void T_10_01_windowRunsFromLocalMidnightToTheEndOfClassDatePlusEditDays() {
        var madrid = AttendanceWindow.of(MONDAY, ZoneId.of("Europe/Madrid"), 1);
        assertThat(madrid.opensAt()).isEqualTo(Instant.parse("2026-08-02T22:00:00Z"));
        assertThat(madrid.editableUntil()).isEqualTo(Instant.parse("2026-08-04T21:59:59Z"));
        assertThat(madrid.notOpen(Instant.parse("2026-08-02T21:59:00Z"))).isTrue();
        assertThat(madrid.notOpen(Instant.parse("2026-08-02T22:00:00Z"))).isFalse();
        assertThat(madrid.closed(Instant.parse("2026-08-04T21:59:59Z"))).isFalse();
        assertThat(madrid.closed(Instant.parse("2026-08-04T22:00:00Z"))).isTrue();
        // Same class date in Buenos Aires (UTC−3): different instants.
        var buenosAires = AttendanceWindow.of(MONDAY, ZoneId.of("America/Argentina/Buenos_Aires"), 1);
        assertThat(buenosAires.opensAt()).isEqualTo(Instant.parse("2026-08-03T03:00:00Z"));
        assertThat(buenosAires.editableUntil()).isEqualTo(Instant.parse("2026-08-05T02:59:59Z"));
        assertThat(buenosAires.notOpen(Instant.parse("2026-08-02T22:00:00Z"))).isTrue();
        // editDays = 0: only the class day; a negative value is a catalog violation.
        assertThat(AttendanceWindow.of(MONDAY, ZoneId.of("Europe/Madrid"), 0).editableUntil()).isEqualTo(Instant.parse("2026-08-03T21:59:59Z"));
        assertThatThrownBy(() -> AttendanceWindow.of(MONDAY, ZoneId.of("Europe/Madrid"), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void T_10_01_windowFollowsTheDstChangeOfTheClubZone() {
        // 2026-10-25: Madrid goes back from CEST to CET; the window closes at 23:59:59 CET of the 25th.
        var autumn = AttendanceWindow.of(LocalDate.of(2026, 10, 24), ZoneId.of("Europe/Madrid"), 1);
        assertThat(autumn.opensAt()).isEqualTo(Instant.parse("2026-10-23T22:00:00Z"));
        assertThat(autumn.editableUntil()).isEqualTo(Instant.parse("2026-10-25T22:59:59Z"));
    }

    @Test void T_10_09_statusIsNoneBeforeT0PendingWhileUnmarkedDoneWhenCompleteAndClosedAfterT1() {
        var window = AttendanceWindow.of(MONDAY, ZoneId.of("Europe/Madrid"), 1);
        Instant before = Instant.parse("2026-08-02T12:00:00Z"), inside = Instant.parse("2026-08-03T06:25:00Z"), after = Instant.parse("2026-08-05T08:00:00Z");
        assertThat(window.status(before, "ACTIVE", 0, 4)).isEqualTo(AttendanceWindow.Status.NONE);
        assertThat(window.status(inside, "ACTIVE", 0, 4)).isEqualTo(AttendanceWindow.Status.PENDING);
        assertThat(window.status(inside, "FINISHED", 3, 4)).isEqualTo(AttendanceWindow.Status.PENDING);
        assertThat(window.status(inside, "FINISHED", 4, 4)).isEqualTo(AttendanceWindow.Status.DONE);
        assertThat(window.status(inside, "ACTIVE", 0, 0)).isEqualTo(AttendanceWindow.Status.DONE);
        assertThat(window.status(after, "ACTIVE", 0, 4)).isEqualTo(AttendanceWindow.Status.CLOSED);
        assertThat(window.status(inside, "DRAFT", 0, 4)).isEqualTo(AttendanceWindow.Status.NONE);
        assertThat(window.status(before, "CANCELLED", 0, 4)).isEqualTo(AttendanceWindow.Status.CLOSED);
    }
}
