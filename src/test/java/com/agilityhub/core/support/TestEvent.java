package com.agilityhub.core.support;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * A publishable stand-in for another context's event in consumer tests. The consumers' `*ForeignEvent` envelopes are
 * read-only and cannot be published (E5-T09), so tests publish this record with the same JSON shape instead.
 */
public record TestEvent(String kind, String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public TestEvent { payload = payload == null ? Map.of() : payload; }
    @Override public String type() { return type != null ? type : kind; }
}
