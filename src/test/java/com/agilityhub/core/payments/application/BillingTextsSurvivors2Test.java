package com.agilityhub.core.payments.application;

import java.time.YearMonth;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT re-run survivor of {@link BillingTexts} (S12 R-12-30; T-12-32): the month's name is the billed month whatever the
 * default time zone. {@code month} formats the 1st at 00:00 UTC, so it must format in UTC: in a zone west of UTC that instant is
 * still the previous month's last day. (A zone east of UTC, such as the Cànic's Europe/Madrid, keeps the 1st and cannot show it.)
 * The test sets the default zone itself and restores it, so it never depends on the JVM's.
 */
class BillingTextsSurvivors2Test {
    final BillingTexts texts = new BillingTexts(null);

    @Test void T_12_32_theMonthNameIsTheBilledMonthEvenWhenTheDefaultZoneIsWestOfUtc() {
        var previousIcu = com.ibm.icu.util.TimeZone.getDefault();
        var previousJdk = java.util.TimeZone.getDefault();
        try {
            com.ibm.icu.util.TimeZone.setDefault(com.ibm.icu.util.TimeZone.getTimeZone("America/Los_Angeles"));

            assertThat(texts.month(YearMonth.of(2026, 9), Locale.ENGLISH)).isEqualTo("September 2026");
            assertThat(texts.month(YearMonth.of(2027, 1), Locale.ENGLISH)).isEqualTo("January 2027");
        } finally {
            com.ibm.icu.util.TimeZone.setDefault(previousIcu);
            java.util.TimeZone.setDefault(previousJdk);
        }
    }
}
