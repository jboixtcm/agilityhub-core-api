package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record TeamMembershipChanged(String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload,
                                    String actorAccountId, Origin origin) implements DomainEvent {
    @Override public String type() { return "MembershipChanged"; }
    @Override public String aggregateType() { return "Membership"; }
    @Override public String impersonatedMemberId() { return null; }
}
