package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.Money;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;

public final class EntryFeeCalculator {
    private EntryFeeCalculator() { }
    public static Money perDog(EntryFee fee, Money standard, boolean billing) {
        if (!billing) { return null; }
        return switch (fee.mode()) {
            case STANDARD -> standard;
            case AMOUNT -> fee.amount();
            case PERCENT -> standard.percent(fee.percent() * 100);
            case NONE -> new Money(0, standard.currency());
        };
    }
    /** A proposal only: the signup/admin flow chooses a catalog tariff; this never changes a plan or price. */
    public static Money familySuggestion(Money firstDogFee, int dogs, int discountFromSecondDog) {
        if (dogs < 1 || discountFromSecondDog < 0 || discountFromSecondDog > 100) { throw new IllegalArgumentException("Invalid family proposal"); }
        Money additional = firstDogFee.percent((100 - discountFromSecondDog) * 100);
        return new Money(Math.addExact(firstDogFee.amountMinor(), Math.multiplyExact(additional.amountMinor(), dogs - 1L)), firstDogFee.currency());
    }
    public record PlanChange(Money amount, String reason) { }
    /** The caller supplies whether this pack has already funded a plan change and records consumption atomically. */
    public static PlanChange forPlanChange(Money entry, PlanType previous, PlanType next, int sessions,
            boolean alreadyApplied, boolean packs, int minimumSessions, int discount) {
        if (entry != null && packs && previous == PlanType.PACK && next == PlanType.MONTHLY && !alreadyApplied && sessions >= minimumSessions) {
            return new PlanChange(entry.percent((100 - discount) * 100), "PACK_TO_MEMBER");
        }
        return new PlanChange(entry, null);
    }
}
