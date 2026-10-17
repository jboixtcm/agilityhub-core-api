package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S17 §7 / event catalog Annex A (E10-T04 round 2, E123): `{clubId, actorAccountId}` when the club's ADMIN ends a live support
 * access of the AgilityHub team (R-17-10 «revocable»); `actorAccountId` is the platform account whose access ended.
 */
public record SupportAccessEnded(String clubId, String aggregateId, Instant occurredAt, String actorAccountId) implements DomainEvent {
    @Override public String type() { return "SupportAccessEnded"; }
    @Override public String aggregateType() { return "ImpersonationGrant"; }
    @Override public String impersonatedMemberId() { return null; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
    @Override public Map<String, Object> payload() { return Map.of("clubId", clubId, "actorAccountId", actorAccountId); }
}
