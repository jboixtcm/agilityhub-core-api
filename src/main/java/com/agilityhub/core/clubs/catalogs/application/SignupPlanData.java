package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.EntryFeeCalculator;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms;
import com.agilityhub.core.clubs.catalogs.domain.PriceRules;
import com.agilityhub.core.clubs.catalogs.persistence.Plan;
import com.agilityhub.core.clubs.catalogs.persistence.Price;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;
import java.time.LocalDate;
import java.util.List;

/** Catalog application projection for pure signup calculations; source records never escape it. */
public record SignupPlanData(String id, String clubId, Type type, String billingMode, int dogsIncluded,
        LocalizedText name, LocalizedText description, LocalizedText conditions, LocalizedText offerLabel,
        boolean active, boolean showOnSignup, int order, Entry entryFee, Pack pack, List<CurrentPrice> currentPrices) {
    public enum Type { MONTHLY, PACK, SINGLE_CLASS }
    public enum Concept { MONTHLY_FEE, MAINTENANCE_FEE, PACK, SINGLE_CLASS }
    public record Entry(Money amount, Integer percentOfStandard, boolean waived, boolean useStandard) {
        public Entry(Money amount, Integer percentOfStandard, boolean waived) { this(amount, percentOfStandard, waived, false); }
    }
    public record Pack(int sessions, int validityMonths) { }
    public record CurrentPrice(String id, Concept concept, Money amount) { }
    public SignupPlanData { currentPrices = List.copyOf(currentPrices); }
    public CurrentPrice currentPrice(Concept concept) {
        return currentPrices.stream().filter(p -> p.concept() == concept).findFirst().orElse(null);
    }
    public Money resolvedEntryFee(Money standard) {
        OfferTerms.EntryFee fee;
        if (entryFee != null && entryFee.amount() != null) {
            fee = new OfferTerms.EntryFee(OfferTerms.EntryFeeMode.AMOUNT, entryFee.amount(), null);
        } else if (entryFee != null && entryFee.percentOfStandard() != null) {
            fee = new OfferTerms.EntryFee(OfferTerms.EntryFeeMode.PERCENT, null, entryFee.percentOfStandard());
        } else {
            boolean waived = entryFee != null && entryFee.waived()
                    || type != Type.MONTHLY && (entryFee == null || !entryFee.useStandard());
            fee = new OfferTerms.EntryFee(waived ? OfferTerms.EntryFeeMode.NONE : OfferTerms.EntryFeeMode.STANDARD, null, null);
        }
        return EntryFeeCalculator.perDog(fee, standard, true);
    }
    /** Price validity follows the model/S05 inclusive validTo convention used by PriceResolver. */
    public static SignupPlanData from(Plan plan, List<Price> prices, LocalDate today) {
        var fee = plan.entryFee();
        var entry = fee == null ? null : switch (fee.mode()) {
            case AMOUNT -> new Entry(fee.amount(), null, false);
            case PERCENT -> new Entry(null, fee.percent(), false);
            case NONE -> new Entry(null, null, true);
            case STANDARD -> new Entry(null, null, false, true);
        };
        var current = prices.stream().filter(p -> p.clubId().equals(plan.clubId()) && p.planId().equals(plan.id()))
                .filter(p -> PriceRules.supports(plan.type(), p.concept()) && PriceRules.status(p, today) == PriceRules.Status.CURRENT)
                .map(p -> new CurrentPrice(p.id(), Concept.valueOf(p.concept().name()), p.amount())).toList();
        return new SignupPlanData(plan.id(), plan.clubId(), Type.valueOf(plan.type().name()),
                plan.billingMode() == null ? null : plan.billingMode().name(), plan.dogsIncluded(),
                plan.name(), plan.texts() == null ? null : plan.texts().description(), plan.conditions(),
                plan.texts() == null ? null : plan.texts().offerLabel(), plan.active(), plan.showOnSignup(), plan.order(),
                entry, plan.pack() == null ? null : new Pack(plan.pack().sessions(), plan.pack().validityMonths()), current);
    }
}
