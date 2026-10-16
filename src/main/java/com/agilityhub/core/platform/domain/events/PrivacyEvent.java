package com.agilityhub.core.platform.domain.events;

import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S14 §7 erasure events (payload extended by §13-12): `AccountErasureRequested{accountId?, memberIds[], clubIds[], executeAt,
 * source}` and `AccountErased{accountId?, memberIds[], clubIds[]}`; both also carry the `requestId` and the `scope`. An absent
 * `accountId` (never null: the consumers' envelopes copy the payload) means no account is involved or, for `AccountErased`, that
 * the account was kept. Same shape as identity's events, so S01's consumer reads it with its own class.
 */
public record PrivacyEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload,
        String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public enum Kind { AccountErasureRequested, AccountErased }
    public PrivacyEvent {
        payload = ImmutableValues.map(payload);
        if (origin == null) { origin = Origin.SYSTEM; }
    }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return "ErasureRequest"; }
}
