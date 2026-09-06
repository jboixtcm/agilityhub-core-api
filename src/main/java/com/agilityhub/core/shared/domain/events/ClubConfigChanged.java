package com.agilityhub.core.shared.domain.events;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Configuration changes use the existing ClubUpdated catalog event. */
public record ClubConfigChanged(String clubId, Instant occurredAt, Map<String, Object> payload,
                                String actorAccountId, String impersonatedMemberId, Origin origin)
        implements DomainEvent {
    public ClubConfigChanged {
        Objects.requireNonNull(clubId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(origin);
        payload = Map.copyOf(payload);
    }
    @Override public String type() { return "ClubUpdated"; }
    @Override public String aggregateType() { return "Club"; }
    @Override public String aggregateId() { return clubId; }
}
