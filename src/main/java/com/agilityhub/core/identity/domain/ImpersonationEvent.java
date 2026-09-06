package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record ImpersonationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        String actorAccountId, String impersonatedMemberId) implements DomainEvent {
    public enum Kind { ImpersonationStarted, ImpersonationEnded }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return "ImpersonationGrant"; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
    @Override public Map<String, Object> payload() { return Map.of("actorAccountId", actorAccountId, "memberId", impersonatedMemberId); }
}
