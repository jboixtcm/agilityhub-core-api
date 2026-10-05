package com.agilityhub.core.clubs.census.inactivity.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InactivityCalendarTest {
    static YearMonth m(String month) { return YearMonth.parse(month); }
    @Test void T_13_01_deadlineIsInclusiveAndClampedToTheMonthsLastDay() {
        assertThat(InactivityCalendar.earliest(LocalDate.parse("2026-09-24"), 25)).isEqualTo(m("2026-10"));
        assertThat(InactivityCalendar.earliest(LocalDate.parse("2026-09-25"), 25)).isEqualTo(m("2026-10"));
        assertThat(InactivityCalendar.earliest(LocalDate.parse("2026-09-26"), 25)).isEqualTo(m("2026-11"));
        assertThat(InactivityCalendar.earliest(LocalDate.parse("2027-02-28"), 31)).isEqualTo(m("2027-03"));
        assertThat(InactivityCalendar.earliest(LocalDate.parse("2027-03-01"), 31)).isEqualTo(m("2027-04"));
    }
    @Test void T_13_02_theSameInstantHasDifferentDeadlinesInMadridAndBuenosAires() {
        var instant = Instant.parse("2026-09-25T22:30:00Z");
        assertThat(InactivityCalendar.earliest(instant, 25, ZoneId.of("Europe/Madrid"))).isEqualTo(m("2026-11"));
        assertThat(InactivityCalendar.earliest(instant, 25, ZoneId.of("America/Argentina/Buenos_Aires"))).isEqualTo(m("2026-10"));
        assertThat(InactivityCalendar.from(m("2026-10"), ZoneId.of("Europe/Madrid"))).isEqualTo(Instant.parse("2026-09-30T22:00:00Z"));
        assertThat(InactivityCalendar.until(m("2026-10"), ZoneId.of("Europe/Madrid"))).isEqualTo(Instant.parse("2026-10-31T23:00:00Z"));
        assertThat(InactivityCalendar.until(null, ZoneId.of("Europe/Madrid"))).isNull();
    }
    @Test void T_13_03_shorteningExtendingAndClosingAnOpenPeriodOnlyFlipModifiableMonths() {
        InactivityCalendar.change(m("2026-10"), m("2026-12"), m("2026-10"), m("2026-11"), m("2026-12"));
        fails(() -> InactivityCalendar.change(m("2026-10"), m("2026-12"), m("2026-10"), m("2026-11"), m("2027-01")), ErrorCode.INACTIVITY_DEADLINE_PASSED);
        InactivityCalendar.change(m("2026-10"), m("2026-12"), m("2026-10"), m("2027-02"), m("2027-01"));
        fails(() -> InactivityCalendar.change(m("2026-10"), m("2026-12"), m("2026-10"), m("2027-02"), m("2027-02")), ErrorCode.INACTIVITY_DEADLINE_PASSED);
        InactivityCalendar.change(m("2026-09"), null, m("2026-09"), m("2026-11"), m("2026-12"));
        fails(() -> InactivityCalendar.change(m("2026-09"), null, m("2026-09"), m("2026-11"), m("2027-01")), ErrorCode.INACTIVITY_DEADLINE_PASSED);
        InactivityCalendar.change(m("2026-09"), m("2026-11"), m("2026-09"), null, m("2026-12"));
    }
    @Test void T_13_04_rangesRejectReversedAndTooDistantMonthsAndDetectAdjacency() {
        assertThat(InactivityCalendar.overlapsOrAdjacent(m("2026-11"), m("2026-12"), m("2026-12"), m("2027-01"))).isTrue();
        assertThat(InactivityCalendar.overlapsOrAdjacent(m("2026-11"), m("2026-12"), m("2027-01"), m("2027-01"))).isTrue();
        assertThat(InactivityCalendar.overlapsOrAdjacent(m("2026-11"), m("2026-12"), m("2027-02"), null)).isFalse();
        assertThat(InactivityCalendar.overlapsOrAdjacent(m("2026-11"), null, m("2027-02"), null)).isTrue();
        fails(() -> InactivityCalendar.range(m("2026-11"), m("2026-10")), ErrorCode.INACTIVITY_INVALID_RANGE);
        fails(() -> InactivityCalendar.request(m("2027-12"), null, m("2026-11"), 12, false), ErrorCode.INACTIVITY_INVALID_RANGE);
        fails(() -> InactivityCalendar.request(m("2026-10"), null, m("2026-11"), 12, false), ErrorCode.INACTIVITY_DEADLINE_PASSED);
        InactivityCalendar.request(m("2026-10"), null, m("2026-11"), 12, true);
        assertThat(InactivityCalendar.covers(m("2026-11"), null, m("2027-01"))).isTrue();
        assertThat(InactivityCalendar.covers(m("2026-11"), m("2026-12"), m("2027-01"))).isFalse();
        assertThat(InactivityCalendar.covers(m("2026-11"), null, m("2026-10"))).isFalse();
        assertThat(InactivityCalendar.mayChange(m("2026-11"), LocalDate.parse("2026-09-26"), 25)).isTrue();
    }
    static void fails(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
