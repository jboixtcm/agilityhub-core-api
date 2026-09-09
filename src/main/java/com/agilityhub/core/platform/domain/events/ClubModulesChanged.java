package com.agilityhub.core.platform.domain.events;

import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record ClubModulesChanged(String clubId, Instant occurredAt, Map<String, Object> payload,
        String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public ClubModulesChanged { payload = ImmutableValues.map(payload); }
    @Override public String type() { return "ClubModulesChanged"; }
    @Override public String aggregateType() { return "Club"; }
    @Override public String aggregateId() { return clubId; }
}
