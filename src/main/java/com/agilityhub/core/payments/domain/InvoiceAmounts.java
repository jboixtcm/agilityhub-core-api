package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * S12 R-12-09: every amount in minor units of the club's currency. A line's `tax = round_half_even(base × taxPercent / 100)` and
 * `total = base + tax`; the invoice's base, tax and total are the sums of its lines, never recomputed from a total. A price
 * with `billing.taxIncluded` is the line's total: its base is `round_half_even(price × 100 / (100 + taxPercent))` and the tax
 * the rest, so the member pays exactly the price («IVA inclòs», S12 §13; 60,00 € at 21 % → 4959 + 1041 = 6000 either way).
 */
public final class InvoiceAmounts {
    private InvoiceAmounts() { }
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** A line's three amounts. */
    public record Amounts(Money base, BigDecimal taxPercent, Money tax, Money total) { }

    /** A line from its base (a manual adjustment, or a price without `taxIncluded`). */
    public static Amounts fromBase(Money base, BigDecimal taxPercent) {
        BigDecimal percent = percent(taxPercent);
        long tax = BigDecimal.valueOf(base.amountMinor()).multiply(percent).divide(HUNDRED, 0, RoundingMode.HALF_EVEN).longValueExact();
        return new Amounts(base, percent, new Money(tax, base.currency()), new Money(Math.addExact(base.amountMinor(), tax), base.currency()));
    }
    /** A line from a catalog price (or a fee) as the club states its prices (`billing.taxIncluded`). */
    public static Amounts fromPrice(Money price, BigDecimal taxPercent, boolean taxIncluded) {
        if (!taxIncluded) { return fromBase(price, taxPercent); }
        BigDecimal percent = percent(taxPercent);
        long base = BigDecimal.valueOf(price.amountMinor()).multiply(HUNDRED).divide(HUNDRED.add(percent), 0, RoundingMode.HALF_EVEN).longValueExact();
        return new Amounts(new Money(base, price.currency()), percent, new Money(Math.subtractExact(price.amountMinor(), base), price.currency()), price);
    }
    /** The sum of amounts in {@code currency}; another currency is `422 CURRENCY_MISMATCH`. */
    public static Money sum(List<Money> amounts, String currency) {
        long total = 0;
        for (Money amount : amounts) {
            requireCurrency(amount, currency);
            total = Math.addExact(total, amount.amountMinor());
        }
        return new Money(total, currency);
    }
    /** R-12-09: an amount in another currency than the club's is `422 CURRENCY_MISMATCH`. */
    public static void requireCurrency(Money amount, String currency) {
        if (!amount.currency().equals(currency)) { throw new ApiException(ErrorCode.CURRENCY_MISMATCH); }
    }
    private static BigDecimal percent(BigDecimal taxPercent) {
        BigDecimal percent = taxPercent == null ? BigDecimal.ZERO : taxPercent;
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) { throw new IllegalArgumentException("taxPercent"); }
        return percent;
    }
}
