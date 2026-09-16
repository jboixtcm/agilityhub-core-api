package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** Closed catalog event envelope; publication remains inside the owning lifecycle transaction. */
public record SchedulingEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public SchedulingEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        WeekTemplateChanged("WeekTemplate"),
        WeekGenerated("Week"),
        WeekValidated("Week"),
        ClassSessionCreated("ClassSession"),
        ClassSessionUpdated("ClassSession"),
        ClassCancelledByClub("ClassSession"),
        ClassRiskExemptionChanged("ClassSession"),
        RingBlockCreated("RingBlock"),
        RingBlockUpdated("RingBlock"),
        RingBlockCancelled("RingBlock");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
