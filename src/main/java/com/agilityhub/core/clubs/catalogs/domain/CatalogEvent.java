package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record CatalogEvent(CatalogKind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId) implements DomainEvent {
    @Override public String type() { return kind.eventType(); }
    @Override public String aggregateType() { return kind.entityType(); }
    @Override public String impersonatedMemberId() { return null; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
}
