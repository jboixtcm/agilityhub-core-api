package com.agilityhub.core.clubs.census.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S13 §7 events (CATALEG_ESDEVENIMENTS «Inactivitat i baixa» and Annex A), published through the outbox in the writing
 * transaction (E8-T05 publishes them; E8-T01 declares the contract). Every payload names its period or request and the member;
 * the actor and the origin travel in the envelope (`actorAccountId`, `impersonatedMemberId`, `origin`):
 * `InactivityRequested{periodId, memberId, from, to}` · `InactivityResolved{periodId, memberId, decision, from, to, fee,
 * cancelledBookings}` · `InactivityStarted{periodId, memberId, from, to}` · `InactivityEnded{periodId, memberId, from, to,
 * finishReason}` · `InactivityChanged{periodId, memberId, before, after, cancelledBookings}` · `InactivityCancelled{periodId,
 * memberId, by, reason}` · `LeaveRequested{requestId, memberId, requestedDate, reasonKey}` · `LeaveResolved{requestId, memberId,
 * decision, source, effectiveDate, cancelledBookings}` · `LeaveCancelled{requestId, memberId, by, reason}`.
 * `MemberStatusChanged` keeps its own shared event (S03).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CensusLifecycleEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public CensusLifecycleEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        InactivityRequested("InactivityPeriod"), InactivityResolved("InactivityPeriod"), InactivityStarted("InactivityPeriod"),
        InactivityEnded("InactivityPeriod"), InactivityChanged("InactivityPeriod"), InactivityCancelled("InactivityPeriod"),
        LeaveRequested("LeaveRequest"), LeaveResolved("LeaveRequest"), LeaveCancelled("LeaveRequest");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
