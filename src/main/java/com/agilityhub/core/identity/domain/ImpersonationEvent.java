package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S01 §7: `ImpersonationStarted` and `ImpersonationEnded` {actorAccountId, memberId} (catalog). INC-37: `ImpersonationEnded` also
 * carries `grantId` and `reason` (`LOGOUT` for «Surt», `EXPIRED` when the grant ran out), as S01 §7 lists them.
 */
public record ImpersonationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        String actorAccountId, String impersonatedMemberId, String reason) implements DomainEvent {
    public enum Kind { ImpersonationStarted, ImpersonationEnded }
    public ImpersonationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, String actorAccountId, String impersonatedMemberId) {
        this(kind, clubId, aggregateId, occurredAt, actorAccountId, impersonatedMemberId, null);
    }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return "ImpersonationGrant"; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
    @Override public Map<String, Object> payload() {
        if (kind == Kind.ImpersonationEnded) {
            return Map.of("actorAccountId", actorAccountId, "memberId", impersonatedMemberId, "grantId", aggregateId, "reason", reason);
        }
        return Map.of("actorAccountId", actorAccountId, "memberId", impersonatedMemberId);
    }
}
