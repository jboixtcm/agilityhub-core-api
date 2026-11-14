package com.agilityhub.core.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SEPA-10 (owner decision of 13-11-2026), S12 R-12-11: before the month is generated D6 shows the simulated total and its difference
 * with the total of the previous month's run, and asks for a second explicit confirmation when the difference, up or down, is more
 * than `billing.totalDifferenceConfirmPercent` of the previous total — or when there is no previous total to compare with (no live
 * run that month, or a total of zero). Amounts in minor units of one currency.
 */
public final class TotalComparison {
    private TotalComparison() { }

    public static boolean secondConfirmation(Long previousMinor, long currentMinor, int thresholdPercent) {
        if (previousMinor == null || previousMinor <= 0) { return true; }
        return Math.abs(currentMinor - previousMinor) * 100 > (long) thresholdPercent * previousMinor;
    }

    /** The signed difference as a percentage of the previous total, one decimal (half up); null without a positive previous total. */
    public static BigDecimal percent(Long previousMinor, long currentMinor) {
        if (previousMinor == null || previousMinor <= 0) { return null; }
        return BigDecimal.valueOf(currentMinor - previousMinor).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(previousMinor), 1, RoundingMode.HALF_UP);
    }
}
