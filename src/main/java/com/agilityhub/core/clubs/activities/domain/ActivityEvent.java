package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** Closed catalog event envelope; publication remains inside the owning lifecycle transaction. */
public record ActivityEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public ActivityEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        ActivityPublished("Activity"),
        ActivityUpdated("Activity"),
        ActivityCancelled("Activity"),
        ActivityFinished("Activity"),
        ActivityRegistrationChanged("ActivityRegistration");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
