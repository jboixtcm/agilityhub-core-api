package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** S17 §7 / event catalog Annex A (E10-T04): `{clubId, actorAccountId}` when the AgilityHub team enters a club (R-17-10). */
public record SupportAccessStarted(String clubId, String aggregateId, Instant occurredAt, String actorAccountId) implements DomainEvent {
    @Override public String type() { return "SupportAccessStarted"; }
    @Override public String aggregateType() { return "ImpersonationGrant"; }
    @Override public String impersonatedMemberId() { return null; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
    @Override public Map<String, Object> payload() { return Map.of("clubId", clubId, "actorAccountId", actorAccountId); }
}
