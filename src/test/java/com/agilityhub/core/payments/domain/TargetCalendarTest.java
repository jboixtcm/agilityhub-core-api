package com.agilityhub.core.payments.domain;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** SEPA-04 (c), S12 R-12-15: the TARGET calendar P5i counts its settlement margin on. */
class TargetCalendarTest {
    @ParameterizedTest
    @CsvSource({"2024,2024-03-31", "2025,2025-04-20", "2026,2026-04-05", "2027,2027-03-28", "2028,2028-04-16", "2038,2038-04-25"})
    void SEPA_04_c_easterSundayFollowsTheGregorianComputus(int year, String sunday) {
        assertThat(TargetCalendar.easterSunday(year)).isEqualTo(LocalDate.parse(sunday));
    }

    @Test void SEPA_04_c_weekendsAndTheSixTargetHolidaysAreClosed() {
        for (String closed : new String[] {"2026-01-01", "2026-04-03", "2026-04-06", "2026-05-01", "2026-12-25", "2026-12-26", "2026-09-05", "2026-09-06",
                "2027-03-26", "2027-03-29"}) {
            assertThat(TargetCalendar.businessDay(LocalDate.parse(closed))).as(closed).isFalse();
        }
        // Local holidays are not TARGET holidays: 06-01, 24-06, 11-09, 24-12 and 31-12 are business days.
        for (String open : new String[] {"2026-01-02", "2026-04-02", "2026-04-07", "2026-01-06", "2026-06-24", "2026-09-11", "2026-12-24", "2026-12-31"}) {
            assertThat(TargetCalendar.businessDay(LocalDate.parse(open))).as(open).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"2026-09-01,5,2026-09-08", "2026-09-01,0,2026-09-01", "2026-09-05,0,2026-09-05", "2026-09-05,1,2026-09-07",
            "2026-12-23,5,2026-12-31", "2026-12-31,1,2027-01-04", "2027-03-25,3,2027-04-01", "2026-04-30,1,2026-05-04"})
    void SEPA_04_c_theSettlementDayIsTheNthBusinessDayAfterTheCollectionDate(String from, int days, String expected) {
        assertThat(TargetCalendar.plusBusinessDays(LocalDate.parse(from), days)).isEqualTo(LocalDate.parse(expected));
    }

    @Test void SEPA_04_c_aNegativeMarginIsRefused() {
        assertThatThrownBy(() -> TargetCalendar.plusBusinessDays(LocalDate.parse("2026-09-01"), -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
