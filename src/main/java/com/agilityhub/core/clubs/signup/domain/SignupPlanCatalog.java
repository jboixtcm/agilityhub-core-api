package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.clubs.catalogs.application.SignupPlanData;
import com.agilityhub.core.clubs.catalogs.application.SignupPlanData.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.*;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class SignupPlanCatalog {
    private SignupPlanCatalog() { }
    public record Offer(String id, Type type, String billingMode, int dogsIncluded,
                        LocalizedText name, LocalizedText description, LocalizedText conditions, LocalizedText offerLabel,
                        Pack pack, CurrentPrice price, Money entryFee, Money maintenanceFee) { }
    public static List<Offer> list(String clubId, List<SignupPlanData> plans, Set<Module> modules, Money standardEntryFee) {
        boolean billing = modules.contains(Module.BILLING);
        return plans.stream().filter(p -> p.clubId().equals(clubId) && p.active() && p.showOnSignup())
                .filter(p -> switch (p.type()) {
                    case MONTHLY -> true;
                    case PACK -> modules.contains(Module.PACKS);
                    case SINGLE_CLASS -> modules.contains(Module.SINGLE_CLASS);
                }).sorted(Comparator.comparingInt(SignupPlanData::order).thenComparing(SignupPlanData::id))
                .map(p -> offer(p, billing, standardEntryFee)).toList();
    }
    private static Offer offer(SignupPlanData plan, boolean billing, Money standard) {
        var concept = switch (plan.type()) {
            case MONTHLY -> Concept.MONTHLY_FEE;
            case PACK -> Concept.PACK;
            case SINGLE_CLASS -> Concept.SINGLE_CLASS;
        };
        var price = billing ? plan.currentPrice(concept) : null;
        var maintenance = billing ? plan.currentPrice(Concept.MAINTENANCE_FEE) : null;
        Money entry = billing ? plan.resolvedEntryFee(standard) : null;
        if (billing) {
            checkCurrency(entry, standard);
            if (price != null) { checkCurrency(price.amount(), standard); }
            if (maintenance != null) { checkCurrency(maintenance.amount(), standard); }
        }
        return new Offer(plan.id(), plan.type(), plan.billingMode(), plan.dogsIncluded(), plan.name(), plan.description(),
                plan.conditions(), plan.offerLabel(), plan.pack(), price, entry, maintenance == null ? null : maintenance.amount());
    }
    private static void checkCurrency(Money amount, Money standard) { SignupValidation.nonnegative(amount).plus(new Money(0, standard.currency())); }
    public static Offer require(List<Offer> offers, String planId) {
        return offers.stream().filter(o -> o.id().equals(planId)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.PLAN_NOT_AVAILABLE));
    }
}
