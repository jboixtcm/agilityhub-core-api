package com.agilityhub.core.clubs.training.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** S09 §7 catalog events; TrainingCancelled carries the Annex A extension (by, cancelReason, late). */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public record TrainingEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public TrainingEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return "TrainingBooking"; }
    public enum Kind { TrainingBooked, TrainingCancelled }
}
