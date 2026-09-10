package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.clubs.catalogs.application.SignupPlanData.Type;
import com.agilityhub.core.shared.domain.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static com.agilityhub.core.clubs.signup.domain.FirstMonthCalculator.Option;

/** Immutable proposals. E3-T03 freezes the selected quote and persists payment records transactionally. */
public final class UpfrontLines {
    private UpfrontLines() { }
    public enum Concept { ENTRY_FEE, FIRST_MONTH, PACK, ADDITIONAL_DOG_FEE }
    public record Line(Concept concept, String dogId, Money amountDue) {
        public Line { SignupValidation.nonnegative(amountDue); }
    }
    public record Period(Option option, LocalDate startDate, Money amountDue) { }
    public record Quote(List<Line> lines, Money totalDue, Period firstMonth, Period additionalDog) {
        public Quote { lines = List.copyOf(lines); }
    }
    public static Quote publicSignup(SignupPlanCatalog.Offer plan, String dogId, boolean billing,
            LocalDate today, SignupParameters parameters, Option option) {
        var lines = new ArrayList<Line>(); Period firstMonth = null;
        if (billing && plan != null) {
            entry(lines, dogId, plan.entryFee());
            if (plan.type() == Type.MONTHLY && "MONTHLY_FEE".equals(plan.billingMode()) && plan.price() != null) {
                var choice = FirstMonthCalculator.options(today, parameters.firstMonthSplitDay(),
                        parameters.nextInvoiceDayOfMonth(), plan.price().amount()).stream()
                        .filter(c -> c.option() == option).findFirst().orElseThrow(() -> SignupValidation.field("firstMonthOption"));
                firstMonth = new Period(choice.option(), choice.startDate(), choice.amountDue());
                lines.add(new Line(Concept.FIRST_MONTH, dogId, choice.amountDue()));
            } else if (plan.type() == Type.PACK && plan.price() != null) {
                lines.add(new Line(Concept.PACK, dogId, plan.price().amount()));
            }
        }
        return quote(lines, parameters.entryFeePerDog().currency(), firstMonth, null);
    }
    public static List<Period> additionalDogOptions(LocalDate today, Money additionalMonthlyFee, int cutoffDay) {
        SignupValidation.day(cutoffDay); SignupValidation.nonnegative(additionalMonthlyFee);
        var immediate = new Period(Option.TODAY, today, additionalMonthlyFee);
        return today.getDayOfMonth() <= cutoffDay ? List.of(immediate,
                new Period(Option.ALTERNATIVE, today.plusMonths(1).withDayOfMonth(1), new Money(0, additionalMonthlyFee.currency())))
                : List.of(immediate);
    }
    /** Prefer the actual catalog fare delta; the configured discount is only a fallback proposal. */
    public static Money additionalMonthlyFee(Money currentFare, Money resultingFare, Money standardFare, int discountPercent) {
        if (resultingFare != null && currentFare != null) {
            Money delta = SignupValidation.nonnegative(resultingFare).minus(SignupValidation.nonnegative(currentFare));
            return new Money(Math.max(0, delta.amountMinor()), delta.currency());
        }
        if (discountPercent < 0 || discountPercent > 100) { throw SignupValidation.field("discountPercent"); }
        return SignupValidation.nonnegative(standardFare).percent((100 - discountPercent) * 100);
    }
    public static Quote addDog(SignupPlanCatalog.Offer plan, String dogId, boolean billing, LocalDate today,
            SignupParameters parameters, Money additionalMonthlyFee, Option option) {
        var lines = new ArrayList<Line>(); Period additional = null;
        if (billing && plan != null) {
            entry(lines, dogId, plan.entryFee());
            additional = additionalDogOptions(today, additionalMonthlyFee, parameters.upfrontCutoffDay()).stream()
                    .filter(c -> c.option() == option).findFirst().orElseThrow(() -> SignupValidation.field("firstMonthOption"));
            if (additional.amountDue().amountMinor() > 0) { lines.add(new Line(Concept.ADDITIONAL_DOG_FEE, dogId, additional.amountDue())); }
        }
        return quote(lines, parameters.entryFeePerDog().currency(), null, additional);
    }
    private static void entry(List<Line> lines, String dogId, Money entry) {
        if (entry != null && entry.amountMinor() > 0) { lines.add(new Line(Concept.ENTRY_FEE, dogId, entry)); }
    }
    private static Quote quote(List<Line> lines, String currency, Period first, Period additional) {
        Money total = new Money(0, currency);
        for (var line : lines) { total = total.plus(line.amountDue()); }
        return new Quote(lines, total, first, additional);
    }
}
