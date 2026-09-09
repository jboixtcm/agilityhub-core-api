package com.agilityhub.core.clubs.content.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record ClubPageChanged(String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, Origin origin) implements DomainEvent {
    @Override public String type() { return "ClubPageChanged"; }
    @Override public String aggregateType() { return "ClubPage"; }
    @Override public String impersonatedMemberId() { return null; }
}
