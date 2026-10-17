package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT session-10 survivors of {@link InvoiceAmounts} (S12 R-12-09, T-12-05): a tax percent is valid from 0 to 100,
 * both bounds included; above 100 it is refused.
 */
class InvoiceAmountsSurvivors3Test {
    static Money eur(long amount) { return new Money(amount, "EUR"); }

    @Test void T_12_05_aTaxOfExactlyAHundredPercentIsAcceptedAndAboveItRefused() {
        var fromBase = InvoiceAmounts.fromBase(eur(1000), new BigDecimal("100"));
        assertThat(fromBase.taxPercent()).isEqualByComparingTo("100");
        assertThat(fromBase.tax()).isEqualTo(eur(1000));
        assertThat(fromBase.total()).isEqualTo(eur(2000));

        var fromPrice = InvoiceAmounts.fromPrice(eur(2000), new BigDecimal("100"), true);
        assertThat(fromPrice.base()).isEqualTo(eur(1000));
        assertThat(fromPrice.tax()).isEqualTo(eur(1000));

        assertThat(InvoiceAmounts.fromBase(eur(1000), BigDecimal.ZERO).tax()).isEqualTo(eur(0));
        assertThatThrownBy(() -> InvoiceAmounts.fromBase(eur(1000), new BigDecimal("100.01"))).isInstanceOf(IllegalArgumentException.class);
    }
}
