package com.agilityhub.core.platform.domain.events;

import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record ParameterChanged(String clubId, Instant occurredAt, Map<String, Object> payload,
                               String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public ParameterChanged { payload = ImmutableValues.map(payload); }
    @Override public String type() { return "ParameterChanged"; }
    @Override public String aggregateType() { return "Parameter"; }
    @Override public String aggregateId() { return (String) payload.get("key"); }
}
