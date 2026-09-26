package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.clubs.bookings.domain.AttendanceMetrics.ClassRecord;
import com.agilityhub.core.clubs.bookings.domain.AttendanceMetrics.Display;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.bookings.domain.AttendanceState.*;
import static org.assertj.core.api.Assertions.*;

/** S10 R-10-08 (30-day metrics) and R-10-09 (last classes) of the student card, without Spring or Mongo. */
class AttendanceMetricsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = LocalDateTime.parse("2026-08-20T12:00").atZone(MADRID).toInstant();
    static final Instant FROM = NOW.atZone(MADRID).minusDays(30).toInstant();
    static int n;
    static ClassRecord record(int daysAgo, BookingState booking, AttendanceState attendance, boolean afterClassEnd) {
        return new ClassRecord("b" + (++n), NOW.atZone(MADRID).minusDays(daysAgo).toInstant(), booking, attendance, afterClassEnd);
    }

    @Test void T_10_04_presentSixNoShowOneNotifiedOneIs86PercentOverSeven() {
        var records = new ArrayList<ClassRecord>();
        for (int i = 1; i <= 6; i++) { records.add(record(i * 3, BookingState.ACTIVE, PRESENT, false)); }
        records.add(record(20, BookingState.ACTIVE, NO_SHOW, false));
        records.add(record(21, BookingState.CANCELLED, NOTIFIED, false)); // «ha avisat» in time: informative only
        var metrics = AttendanceMetrics.of(records, FROM, NOW);
        assertThat(metrics).isEqualTo(new AttendanceMetrics.Metrics(30, 86, 6, 1, 1, 0, 7));
        // + one late cancellation by the member → 6/8 = 75 %, 8 classes.
        records.add(record(22, BookingState.CANCELLED_LATE, PENDING, false));
        assertThat(AttendanceMetrics.of(records, FROM, NOW)).isEqualTo(new AttendanceMetrics.Metrics(30, 75, 6, 1, 1, 1, 8));
        // + a NOTIFIED after the class end (booking still ACTIVE) and a late «ha avisat» (CANCELLED_LATE) → both cancelledLate.
        records.add(record(23, BookingState.ACTIVE, NOTIFIED, true));
        records.add(record(24, BookingState.CANCELLED_LATE, NOTIFIED, false));
        assertThat(AttendanceMetrics.of(records, FROM, NOW)).isEqualTo(new AttendanceMetrics.Metrics(30, 60, 6, 1, 1, 3, 10));
    }

    @Test void T_10_04_nothingToCountGivesNullAndClubCancellationsOrUnmarkedPastClassesCountNowhere() {
        assertThat(AttendanceMetrics.of(List.of(), FROM, NOW)).isEqualTo(new AttendanceMetrics.Metrics(30, null, 0, 0, 0, 0, 0));
        var records = List.of(record(2, BookingState.CANCELLED_BY_CLUB, PENDING, false), record(3, BookingState.ACTIVE, PENDING, false),
                record(4, BookingState.CANCELLED, PENDING, false), record(5, BookingState.CANCELLED_BY_CLUB, PRESENT, false));
        assertThat(AttendanceMetrics.of(records, FROM, NOW)).isEqualTo(new AttendanceMetrics.Metrics(30, null, 0, 0, 0, 0, 0));
    }

    @Test void T_10_04_theWindowIsThirtyClubDaysUpToNowExcluded() {
        var atStart = new ClassRecord("start", FROM, BookingState.ACTIVE, PRESENT, false);
        var before = new ClassRecord("before", FROM.minusSeconds(1), BookingState.ACTIVE, PRESENT, false);
        var atNow = new ClassRecord("now", NOW, BookingState.ACTIVE, NO_SHOW, false);
        assertThat(AttendanceMetrics.of(List.of(atStart, before, atNow), FROM, NOW)).isEqualTo(new AttendanceMetrics.Metrics(30, 100, 1, 0, 0, 0, 1));
        // 30 club days across the October DST change are 30 × 24 h + 1 h.
        var autumn = LocalDateTime.parse("2026-11-10T12:00").atZone(MADRID);
        assertThat(Duration.between(autumn.minusDays(30).toInstant(), autumn.toInstant())).isEqualTo(Duration.ofHours(30 * 24 + 1));
    }

    @Test void T_10_04_tenTrainingsInThirtyDaysAre2Point3PerWeek() {
        assertThat(AttendanceMetrics.perWeek(10)).isEqualTo(2.3);
        assertThat(AttendanceMetrics.perWeek(0)).isEqualTo(0.0);
        assertThat(AttendanceMetrics.perWeek(13)).isEqualTo(3.0);
    }

    @Test void T_10_05_lastClassesAreTheFiveNewestStartedWithTheirBadge() {
        var records = List.of(
                record(1, BookingState.ACTIVE, PRESENT, false), record(2, BookingState.ACTIVE, NO_SHOW, false), record(3, BookingState.CANCELLED, NOTIFIED, false),
                record(4, BookingState.CANCELLED_LATE, PENDING, false), record(5, BookingState.ACTIVE, PENDING, false), record(6, BookingState.ACTIVE, PRESENT, false),
                record(7, BookingState.CANCELLED, PENDING, false), record(8, BookingState.CANCELLED_BY_CLUB, PENDING, false),
                record(-2, BookingState.ACTIVE, PENDING, false), record(9, BookingState.PAYMENT_PENDING, PENDING, false));
        var last = AttendanceMetrics.lastClasses(records, NOW);
        assertThat(last).hasSize(5);
        assertThat(last.stream().map(AttendanceMetrics.LastClass::display)).containsExactly(Display.PRESENT, Display.NO_SHOW, Display.NOTIFIED,
                Display.CANCELLED_LATE, Display.PENDING);
        assertThat(last.stream().map(l -> l.record().classStartsAt())).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // The sixth started class is left out; in-time cancellations, club cancellations, pending payments and future classes never show.
        var all = AttendanceMetrics.lastClasses(records, NOW.plus(Duration.ofDays(10)));
        assertThat(all).hasSize(5).extracting(l -> l.record().classStartsAt()).first().isEqualTo(NOW.atZone(MADRID).plusDays(2).toInstant());
        assertThat(AttendanceMetrics.display(record(1, BookingState.ACTIVE, NOTIFIED, true))).contains(Display.NOTIFIED);
        assertThat(AttendanceMetrics.display(record(1, BookingState.CANCELLED_LATE, NOTIFIED, false))).contains(Display.NOTIFIED);
        assertThat(AttendanceMetrics.display(record(1, BookingState.CANCELLED, PENDING, false))).isEmpty();
        assertThat(AttendanceMetrics.display(record(1, BookingState.CANCELLED_BY_CLUB, NOTIFIED, false))).isEmpty();
    }
}
