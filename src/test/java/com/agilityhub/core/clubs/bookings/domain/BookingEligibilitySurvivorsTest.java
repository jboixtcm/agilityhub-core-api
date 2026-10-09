package com.agilityhub.core.clubs.bookings.domain;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E11-T06 PIT survivors of {@link BookingEligibility#covers} (S08 R-08-06): both ends inclusive, an open end has no upper bound. */
class BookingEligibilitySurvivorsTest {
    static final BookingEligibility.Period OCTOBER = new BookingEligibility.Period(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

    @Test void T_08_23_aClosedPeriodCoversItsEndsAndNothingAfter() {
        assertThat(BookingEligibility.covers(OCTOBER, LocalDate.parse("2026-10-01"))).isTrue();
        assertThat(BookingEligibility.covers(OCTOBER, LocalDate.parse("2026-10-31"))).isTrue();
        assertThat(BookingEligibility.covers(OCTOBER, LocalDate.parse("2026-11-01"))).isFalse();
        assertThat(BookingEligibility.covers(OCTOBER, LocalDate.parse("2026-09-30"))).isFalse();
    }

    @Test void T_08_23_anOpenPeriodCoversEveryDayFromItsStart() {
        var open = new BookingEligibility.Period(LocalDate.parse("2026-10-01"), null);

        assertThat(BookingEligibility.covers(open, LocalDate.parse("2027-03-15"))).isTrue();
    }
}
