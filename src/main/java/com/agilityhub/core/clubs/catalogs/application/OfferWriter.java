package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OfferWriter {
    private final PlanRepository plans;
    private final PriceRepository prices;
    private final EventPublisher events;
    private final AuditActorProvider actors;
    private final Clock clock;
    private final ObjectMapper mapper;
    public OfferWriter(PlanRepository plans, PriceRepository prices, EventPublisher events, AuditActorProvider actors, Clock clock, ObjectMapper mapper) {
        this.plans = plans; this.prices = prices; this.events = events; this.actors = actors; this.clock = clock; this.mapper = mapper;
    }
    public Map<String, Object> fields(OfferEntity item) {
        if (item == null) { return Map.of(); }
        Map<String, Object> fields = mapper.convertValue(item, new TypeReference<>() { });
        for (String key : List.of("clubId", "version", "createdAt", "updatedAt", "createdByAccountId", "updatedByAccountId")) { fields.remove(key); }
        return fields;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "#kind.name()", entity = "#id", before = "#before")
    public Map<String, Object> save(OfferChanged.Kind kind, String id, Map<String, Object> before, OfferEntity next, String action) {
        if (next instanceof Plan plan) {
            if (before.isEmpty()) { plans.insert(plan); } else { plans.update(plan, plan.version() - 1); }
        } else if (next instanceof Price price) {
            if (before.isEmpty()) { prices.insert(price); } else { prices.update(price, price.version() - 1); }
        } else if (kind == OfferChanged.Kind.Plan) { plans.deleteById(id); }
        else { prices.deleteById(id); }
        var after = fields(next); var keys = new LinkedHashSet<>(before.keySet()); keys.addAll(after.keySet()); keys.remove("id");
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                Map<String, Object> change = new LinkedHashMap<>(); change.put("before", before.get(key)); change.put("after", after.get(key)); diff.put(key, change);
            }
        }
        events.publish(new OfferChanged(kind, com.agilityhub.core.shared.application.TenantContext.require(), id, clock.instant(),
                Map.of("id", id, "action", action, "diff", diff), actors.current().accountId()));
        return next == null ? null : after;
    }
}
