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
        Money half = new Money(BigDecimal.valueOf(monthlyPrice.amountMinor()).divide(BigDecimal.valueOf(2), 0,
                RoundingMode.HALF_UP).longValueExact(), monthlyPrice.currency());
        LocalDate split = day(YearMonth.from(today), splitDay);
        boolean early = today.isBefore(split);
        LocalDate alternative = early ? split : today.plusMonths(1).withDayOfMonth(1);
        return List.of(new Choice(Option.TODAY, today, early ? monthlyPrice : half,
                        early ? Portion.FULL_MONTH : Portion.HALF_MONTH, nextInvoice(today, invoiceDay)),
                new Choice(Option.ALTERNATIVE, alternative, early ? half : monthlyPrice,
                        early ? Portion.HALF_MONTH : Portion.FULL_MONTH, nextInvoice(alternative, invoiceDay)));
    }
    public static LocalDate nextInvoice(LocalDate startDate, int invoiceDay) {
        SignupValidation.day(invoiceDay);
        return day(YearMonth.from(startDate).plusMonths(1), invoiceDay);
    }
    /** Catalog day 29–31 is clamped to the last day in shorter months. */
    private static LocalDate day(YearMonth month, int day) { return month.atDay(Math.min(day, month.lengthOfMonth())); }
}
