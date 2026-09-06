package com.agilityhub.core.shared.domain;

import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.util.Currency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public record Money(long amountMinor, String currency) {
    public Money {
        java.util.Currency.getInstance(currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money percent(int bp) {
        long result = BigDecimal.valueOf(amountMinor).multiply(BigDecimal.valueOf(bp))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN).longValueExact();
        return new Money(result, currency);
    }

    public String format(Locale locale) {
        Currency unit = Currency.getInstance(currency);
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(unit);
        return format.format(BigDecimal.valueOf(amountMinor, unit.getDefaultFractionDigits()));
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH);
        }
    }
}
