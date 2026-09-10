package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.domain.Money;

/** FAMILY identifies a proposal, not a persisted Price.scope (the model uses a multi-dog Plan). */
public final class FamilyFareProposal {
    private FamilyFareProposal() { }
    public enum Kind { STANDARD, FAMILY }
    public record Fare(String planId, String priceId, Money amount) { }
    public record Proposal(Kind kind, Fare fare, Integer discountPercentHint) { }
    public static Proposal propose(java.util.List<FamilyHolderMatcher.DogStatus> dogs, Fare standard, Fare family, int discountPercent) {
        int eligible = Math.toIntExact(dogs.stream().filter(status -> status == FamilyHolderMatcher.DogStatus.ACTIVE
                || status == FamilyHolderMatcher.DogStatus.PENDING).count());
        return propose(eligible, standard, family, discountPercent);
    }
    /** The catalog caller supplies an eligible family plan/price, if one exists. INACTIVE dogs are excluded. */
    public static Proposal propose(int activeOrPendingDogs, Fare standard, Fare family, int discountPercent) {
        if (activeOrPendingDogs < 0 || discountPercent < 0 || discountPercent > 100) { throw SignupValidation.field("familyGroup"); }
        if (activeOrPendingDogs >= 2 && family != null) { return new Proposal(Kind.FAMILY, family, null); }
        return new Proposal(Kind.STANDARD, standard, activeOrPendingDogs >= 2 ? discountPercent : null);
    }
}
