package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Names are from CATALEG_ESDEVENIMENTS; credentials never enter the outbox. Like every catalog event it records who acted
 * and from where (`actorAccountId`, `impersonatedMemberId`, `origin`; E3-T10): the application services build it with
 * the request's actor, and the short constructor is the system's own event.
 */
public record IdentityEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload,
                            String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public IdentityEvent {
        payload = Map.copyOf(payload);
        if (origin == null) { origin = Origin.SYSTEM; }
    }
    public IdentityEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload) {
        this(kind, clubId, aggregateId, occurredAt, payload, null, null, Origin.SYSTEM);
    }
    public enum Kind { AccountCreated, MagicLinkRequested, PasswordChanged, MembershipChanged, AccountLocaleChanged, SessionRevoked, AccountErasureRequested, LearnAccountsImported }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind == Kind.MembershipChanged ? "Membership" : "Account"; }
}
