package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

public final class FirstMonthCalculator {
    private FirstMonthCalculator() { }
    public enum Option { TODAY, ALTERNATIVE }
    public enum Portion { FULL_MONTH, HALF_MONTH }
    public record Choice(Option option, LocalDate startDate, Money amountDue, Portion portion, LocalDate nextInvoiceDate) { }
    public static List<Choice> options(Clock clock, ZoneId clubTimeZone, SignupParameters parameters, Money monthlyPrice) {
        return options(LocalDate.now(clock.withZone(clubTimeZone)), parameters.firstMonthSplitDay(),
                parameters.nextInvoiceDayOfMonth(), monthlyPrice);
    }
    public static List<Choice> options(LocalDate today, int splitDay, int invoiceDay, Money monthlyPrice) {
        SignupValidation.day(splitDay); SignupValidation.day(invoiceDay); SignupValidation.nonnegative(monthlyPrice);
        Money half = half(monthlyPrice);
        LocalDate split = day(YearMonth.from(today), splitDay);
        boolean early = today.isBefore(split);
        LocalDate alternative = early ? split : today.plusMonths(1).withDayOfMonth(1);
        return List.of(new Choice(Option.TODAY, today, early ? monthlyPrice : half,
                        early ? Portion.FULL_MONTH : Portion.HALF_MONTH, nextInvoice(today, invoiceDay)),
                new Choice(Option.ALTERNATIVE, alternative, early ? half : monthlyPrice,
                        early ? Portion.HALF_MONTH : Portion.FULL_MONTH, nextInvoice(alternative, invoiceDay)));
    }
    /**
     * The portion a frozen first month charged, for one frozen before its portion was stored (E3-T12 round 2): its frozen
     * amount against the plan's monthly price when it was frozen, the full price or its half (rounded as in
     * {@link #options}). Never the split day in force today. Null when the amounts cannot tell: no price, another
     * currency, an amount that is neither, or a price whose half equals it (0 or 1 minor unit).
     */
    public static Portion portion(Money amountDue, Money monthlyPrice) {
        if (amountDue == null || monthlyPrice == null || !amountDue.currency().equals(monthlyPrice.currency())) { return null; }
        boolean isFull = amountDue.amountMinor() == monthlyPrice.amountMinor();
        boolean isHalf = amountDue.amountMinor() == half(monthlyPrice).amountMinor();
        return isFull == isHalf ? null : isFull ? Portion.FULL_MONTH : Portion.HALF_MONTH;
    }
    private static Money half(Money monthlyPrice) {
        return new Money(BigDecimal.valueOf(monthlyPrice.amountMinor()).divide(BigDecimal.valueOf(2), 0,
                RoundingMode.HALF_UP).longValueExact(), monthlyPrice.currency());
    }
    public static LocalDate nextInvoice(LocalDate startDate, int invoiceDay) {
        SignupValidation.day(invoiceDay);
        return day(YearMonth.from(startDate).plusMonths(1), invoiceDay);
    }
    /** Catalog day 29–31 is clamped to the last day in shorter months. */
    private static LocalDate day(YearMonth month, int day) { return month.atDay(Math.min(day, month.lengthOfMonth())); }
}
