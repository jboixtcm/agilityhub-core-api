package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** Names are from CATALEG_ESDEVENIMENTS; credentials never enter the outbox. */
public record IdentityEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
                            Map<String, Object> payload) implements DomainEvent {
    public IdentityEvent { payload = Map.copyOf(payload); }
    public enum Kind { AccountCreated, MagicLinkRequested, PasswordChanged, MembershipChanged, AccountLocaleChanged, SessionRevoked, AccountErasureRequested }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind == Kind.MembershipChanged ? "Membership" : "Account"; }
    @Override public String actorAccountId() { return null; }
    @Override public String impersonatedMemberId() { return null; }
    @Override public Origin origin() { return Origin.SYSTEM; }
}
