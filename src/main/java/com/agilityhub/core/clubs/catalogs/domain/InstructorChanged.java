package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record InstructorChanged(String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload,
                                String actorAccountId, Origin origin) implements DomainEvent {
    @Override public String type() { return "InstructorChanged"; }
    @Override public String aggregateType() { return "Instructor"; }
    @Override public String impersonatedMemberId() { return null; }
}
