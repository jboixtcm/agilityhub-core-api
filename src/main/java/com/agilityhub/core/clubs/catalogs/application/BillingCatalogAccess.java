package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import com.agilityhub.core.clubs.catalogs.persistence.PlanRepository;
import com.agilityhub.core.clubs.catalogs.persistence.PriceRepository;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * The S05 terms S12's invoicing reads (E8-T02, R-12-02/03): a plan's type, billing mode and name, and the price of a concept
 * current on a day through {@link PriceResolver}. Tenant-scoped; the catalog records never leave this context (payments
 * reaches `clubs.catalogs.application` only, ArchUnit `PAYMENTS_CLUB_DEPENDENCIES`). Types and concepts are the catalog's
 * names as strings.
 */
@Service
public class BillingCatalogAccess {
    /** `type` MONTHLY · PACK · SINGLE_CLASS; `billingMode` MONTHLY_FEE · MAINTENANCE (B10), null = MONTHLY_FEE; `chargeMode` of a single class. */
    public record BillingPlan(String id, String code, String type, String billingMode, int dogsIncluded, LocalizedText name, String chargeMode) { }
    /** A price current on the asked day: its amount (club currency or not, R-12-09) and its tax percentage (null = 0). */
    public record BillingPrice(String id, Money amount, BigDecimal taxPercent) { }
    public record PackTerms(String planId, int sessions, int validityMonths) { }

    /** Pack terms remain behind the catalog application boundary. Unknown plans are always 404. */
    public PackTerms pack(String planId) {
        var plan = plans.findById(planId).orElseThrow(() -> new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND));
        if (plan.pack() == null || plan.type() != com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PlanType.PACK) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.PLAN_NOT_PACK);
        }
        return new PackTerms(plan.id(), plan.pack().sessions(), plan.pack().validityMonths());
    }

    @org.springframework.beans.factory.annotation.Autowired private PlanService planService;
    public record ChangeQuote(Money amount, String reason) { }
    public void validateChange(String planId, String priceId, LocalDate day) {
        var plan = planService.get(planId);
        var price = prices.findById(priceId).orElseThrow(() -> new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND));
        if (!planService.offered(plan) || !planId.equals(price.planId())) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.PLAN_NOT_AVAILABLE);
        }
    }
    public ChangeQuote changeQuote(String previous, String next, int sessions, boolean alreadyApplied) {
        var quote = planService.forPlanChange(planService.get(previous), planService.get(next), sessions, alreadyApplied);
        return new ChangeQuote(quote.amount(), quote.reason());
    }
    private final PlanRepository plans; private final PriceRepository prices; private final PriceResolver resolver;
    public BillingCatalogAccess(PlanRepository plans, PriceRepository prices, PriceResolver resolver) {
        this.plans = plans; this.prices = prices; this.resolver = resolver;
    }

    public Optional<BillingPlan> plan(String planId) {
        if (planId == null) { return Optional.empty(); }
        return plans.findById(planId).map(plan -> new BillingPlan(plan.id(), plan.code(), plan.type().name(),
                plan.billingMode() == null ? null : plan.billingMode().name(), plan.dogsIncluded(), plan.name(),
                plan.singleClass() == null || plan.singleClass().chargeMode() == null ? null : plan.singleClass().chargeMode().name()));
    }
    /** `PriceResolver.current(planId, concept, day)` (S05): empty without a price of that concept valid on {@code day}. */
    public Optional<BillingPrice> currentPrice(String planId, String concept, LocalDate day) {
        if (planId == null) { return Optional.empty(); }
        return resolver.current(planId, PriceConcept.valueOf(concept), day)
                .map(price -> new BillingPrice(price.id(), price.amount(), price.taxPercent()));
    }
    /** A price of the club by id, whatever its validity (the tax of a single class already charged, R-12-25). */
    public Optional<BillingPrice> price(String priceId) {
        if (priceId == null) { return Optional.empty(); }
        return prices.findById(priceId).map(price -> new BillingPrice(price.id(), price.amount(), price.taxPercent()));
    }
    /** R-12-07: the latest change of the club's plans and prices (`SIMULATION_STALE`), if any. */
    public Optional<Instant> lastChange() {
        return Stream.concat(plans.findAll().stream().map(plan -> plan.updatedAt()), prices.findAll().stream().map(price -> price.updatedAt()))
                .filter(Objects::nonNull).max(Instant::compareTo);
    }
}
