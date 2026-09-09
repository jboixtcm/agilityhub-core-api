package com.agilityhub.core.shared.domain.events;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** Shared wire contract for the S03/S13 producer and tenant-bound consumers. */
public record MemberStatusChanged(String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload,
                                  String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    @Override public String type() { return "MemberStatusChanged"; }
    @Override public String aggregateType() { return "Member"; }
}
