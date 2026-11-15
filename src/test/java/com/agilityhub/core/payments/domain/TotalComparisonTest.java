package com.agilityhub.core.payments.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** SEPA-10 (owner decision of 13-11-2026, S12 R-12-11): when D6 asks a second confirmation before generating the month. */
class TotalComparisonTest {
    @Test void SEPA_10_aDifferenceAboveTheThresholdUpOrDownAsksTwice() {
        // 1.000,00 last month: up to 1.200,00 and down to 800,00 is within 20 %; one cent more is not.
        assertThat(TotalComparison.secondConfirmation(100_000L, 120_000, 20)).isFalse();
        assertThat(TotalComparison.secondConfirmation(100_000L, 120_001, 20)).isTrue();
        assertThat(TotalComparison.secondConfirmation(100_000L, 80_000, 20)).isFalse();
        assertThat(TotalComparison.secondConfirmation(100_000L, 79_999, 20)).isTrue();
        assertThat(TotalComparison.secondConfirmation(100_000L, 100_000, 20)).isFalse();
        // 0 % asks for any difference; 100 % only when the total more than doubles.
        assertThat(TotalComparison.secondConfirmation(100_000L, 100_001, 0)).isTrue();
        assertThat(TotalComparison.secondConfirmation(100_000L, 100_000, 0)).isFalse();
        assertThat(TotalComparison.secondConfirmation(100_000L, 200_000, 100)).isFalse();
        assertThat(TotalComparison.secondConfirmation(100_000L, 200_001, 100)).isTrue();
    }

    @Test void SEPA_10_withoutAPreviousTotalTheSecondConfirmationIsAlwaysAsked() {
        assertThat(TotalComparison.secondConfirmation(null, 120_000, 20)).isTrue();
        assertThat(TotalComparison.secondConfirmation(0L, 0, 20)).isTrue();
        assertThat(TotalComparison.percent(null, 120_000)).isNull();
        assertThat(TotalComparison.percent(0L, 120_000)).isNull();
    }

    @Test void SEPA_10_thePercentageIsSignedWithOneDecimal() {
        assertThat(TotalComparison.percent(100_000L, 120_000)).isEqualByComparingTo(new BigDecimal("20.0"));
        assertThat(TotalComparison.percent(100_000L, 79_999)).isEqualByComparingTo(new BigDecimal("-20.0"));
        assertThat(TotalComparison.percent(4_999L, 6_000)).isEqualByComparingTo(new BigDecimal("20.0"));
        assertThat(TotalComparison.percent(30_000L, 40_000)).isEqualByComparingTo(new BigDecimal("33.3"));
        assertThat(TotalComparison.percent(30_000L, 40_000).scale()).isEqualTo(1);
    }
}
