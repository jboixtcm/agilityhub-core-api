package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record OfferChanged(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId) implements DomainEvent {
    public enum Kind { Plan, Price }
    @Override public String type() { return kind.name() + "Changed"; }
    @Override public String aggregateType() { return kind.name(); }
    @Override public String impersonatedMemberId() { return null; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
}
