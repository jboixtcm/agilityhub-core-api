package com.agilityhub.core.shared.domain;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoneyTest {
    @Test void E0_T04_arithmeticIsExactAndRejectsMixedCurrencies() {
        Money amount = new Money(4500, "EUR");
        assertThat(amount.plus(new Money(50, "EUR"))).isEqualTo(new Money(4550, "EUR"));
        assertThat(amount.minus(new Money(4600, "EUR"))).isEqualTo(new Money(-100, "EUR"));
        assertThatThrownBy(() -> amount.plus(new Money(1, "USD"))).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
        assertThatThrownBy(() -> amount.minus(new Money(1, "USD"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, "EUR").plus(new Money(1, "EUR")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Money(Long.MIN_VALUE, "EUR").minus(new Money(1, "EUR")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Money(1, "INVALID")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void E0_T04_basisPointsRoundHalfEvenWithoutOverflow() {
        assertThat(new Money(1, "EUR").percent(5000).amountMinor()).isZero();
        assertThat(new Money(3, "EUR").percent(5000).amountMinor()).isEqualTo(2);
        assertThat(new Money(-3, "EUR").percent(5000).amountMinor()).isEqualTo(-2);
        assertThat(new Money(4500, "EUR").percent(2100).amountMinor()).isEqualTo(945);
        assertThat(new Money(Long.MAX_VALUE, "EUR").percent(10_000).amountMinor()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, "EUR").percent(20_000)).isInstanceOf(ArithmeticException.class);
    }
    @Test void E0_T04_icuFormatsCurrencyWithItsMinorUnitScale() {
        assertThat(new Money(4500, "EUR").format(Locale.forLanguageTag("ca")).replace('\u00a0', ' ')).isEqualTo("45,00 €");
        assertThat(new Money(4500, "EUR").format(Locale.ENGLISH)).isEqualTo("€45.00");
        assertThat(new Money(45, "JPY").format(Locale.US)).isEqualTo("¥45");
        assertThat(new Money(45000, "KWD").format(Locale.US)).contains("45.000");
    }
}
