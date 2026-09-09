package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.*;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PriceService {
    public record Created(String id, String closedPriceId) { }
    private final PlanService plans;
    private final PlanRepository planRepository;
    private final PriceRepository prices;
    private final OfferUsage usage;
    private final OfferWriter writer;
    private final PlanValidator validator;
    private final IdentityTransactions transactions;
    private final AuditActorProvider actors;
    private final Clock clock;
    private final ClubClock clubClock;
    public PriceService(PlanService plans, PlanRepository planRepository, PriceRepository prices, OfferUsage usage, OfferWriter writer,
            PlanValidator validator, IdentityTransactions transactions, AuditActorProvider actors, Clock clock, ClubClock clubClock) {
        this.plans = plans; this.planRepository = planRepository; this.prices = prices; this.usage = usage; this.writer = writer;
        this.validator = validator; this.transactions = transactions; this.actors = actors; this.clock = clock; this.clubClock = clubClock;
    }
    public LocalDate today() { return clubClock.today(TenantContext.require()); }
    private void billing() {
        if (!plans.config().modules().contains(Module.BILLING)) { throw new ApiException(ErrorCode.MODULE_DISABLED, Map.of("module", "BILLING")); }
    }
    public Price get(String id) { return prices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public List<Price> list(String planId, PriceConcept concept) {
        billing(); plans.get(planId);
        return prices.forPlan(planId).stream().filter(price -> concept == null || concept == price.concept()).toList();
    }
    public boolean locked(Price price) { return PriceRules.locked(price, today(), usage.price(price.id()).referenced()); }
    private Price build(Price old, Map<String, Object> request) {
        var values = new LinkedHashMap<>(writer.fields(old)); values.putAll(request);
        String planId = Objects.toString(values.get("planId"), ""); var plan = plans.get(planId);
        var concept = validator.convert(values.get("concept"), PriceConcept.class);
        if (concept == null || (old == null || concept != old.concept()) && !PriceRules.supports(plan.type(), concept)) {
            throw validator.invalid("concept", "VALIDATION_ERROR");
        }
        Money amount = validator.convert(values.get("amount"), Money.class);
        BigDecimal tax = validator.convert(values.get("taxPercent"), BigDecimal.class);
        LocalDate from = validator.convert(values.get("validFrom"), LocalDate.class);
        LocalDate to = validator.convert(values.get("validTo"), LocalDate.class);
        PriceRules.validate(amount, plans.config().club().currency(), tax, from, to);
        var now = clock.instant(); var actor = actors.current().accountId();
        return new Price(old == null ? UUID.randomUUID().toString() : old.id(), TenantContext.require(), planId, concept, amount, tax, from, to,
                old == null ? 0 : old.version() + 1, old == null ? now : old.createdAt(), now, old == null ? actor : old.createdByAccountId(), actor);
    }
    private void save(Price old, Price next, String action) {
        writer.save(OfferChanged.Kind.Price, next == null ? old.id() : next.id(), writer.fields(old), next, action);
    }
    private void overlap(Price next, List<Price> existing) {
        for (Price other : existing) {
            if (!next.id().equals(other.id()) && next.concept() == other.concept() && PriceRules.overlaps(next, other)) {
                throw new ApiException(ErrorCode.PRICE_OVERLAP, Map.of("conflictingPriceId", other.id()));
            }
        }
    }
    private LocalDate billed(Price price) {
        var references = usage.price(price.id());
        if (!references.periodKnown()) { throw new ApiException(ErrorCode.PRICE_LOCKED); }
        return references.lastBilled();
    }
    public Created create(Map<String, Object> request) {
        billing();
        return transactions.run(() -> {
            planRepository.lock(); var next = build(null, request); PriceRules.newStart(next.validFrom(), today(), null);
            var existing = prices.forPlan(next.planId());
            var open = existing.stream().filter(price -> price.concept() == next.concept() && price.validTo() == null
                    && price.validFrom().isBefore(next.validFrom())).findFirst().orElse(null);
            Price closed = null;
            if (open != null) {
                PriceRules.newStart(next.validFrom(), today(), billed(open));
                closed = build(open, Map.of("validTo", next.validFrom().minusDays(1)));
            }
            var revised = new ArrayList<>(existing);
            if (open != null) { revised.remove(open); revised.add(closed); }
            overlap(next, revised);
            if (closed != null) { save(open, closed, "CLOSED"); }
            save(null, next, "CREATED"); return new Created(next.id(), open == null ? null : open.id());
        });
    }
    public void update(String id, Map<String, Object> request) {
        billing();
        transactions.run(() -> {
            planRepository.lock(); var old = get(id);
            if (!(request.get("version") instanceof Number version) || version.longValue() != old.version()) { throw new ApiException(ErrorCode.STALE_VERSION); }
            boolean locked = locked(old);
            // Locked-field errors take precedence over new concept compatibility.
            if (locked) {
                boolean changed = request.containsKey("amount") && !old.amount().equals(validator.convert(request.get("amount"), Money.class))
                        || request.containsKey("taxPercent") && old.taxPercent().compareTo(validator.convert(request.get("taxPercent"), BigDecimal.class)) != 0
                        || request.containsKey("validFrom") && !old.validFrom().equals(validator.convert(request.get("validFrom"), LocalDate.class))
                        || request.containsKey("concept") && old.concept() != validator.convert(request.get("concept"), PriceConcept.class);
                if (changed) { throw new ApiException(ErrorCode.PRICE_LOCKED); }
            }
            var next = build(old, request);
            if (locked && !Objects.equals(old.validTo(), next.validTo())) { PriceRules.close(next.validTo(), today(), billed(old)); }
            if (!locked) { PriceRules.newStart(next.validFrom(), today(), null); }
            overlap(next, prices.forPlan(old.planId())); save(old, next, "UPDATED"); return null;
        });
    }
    public void delete(String id) {
        billing();
        transactions.run(() -> {
            planRepository.lock(); var old = get(id);
            if (locked(old)) { throw new ApiException(ErrorCode.PRICE_LOCKED); }
            save(old, null, "DELETED"); return null;
        });
    }
}
