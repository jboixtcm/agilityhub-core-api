package com.agilityhub.core.migration.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record MigrationEvent(String type, String aggregateId, String clubId, Instant occurredAt,
        Map<String,Object> payload) implements DomainEvent {
    public MigrationEvent {
        if (!java.util.Set.of("MigrationRunStarted","MigrationRunCompleted","MigrationRunFailed").contains(type)) {
            throw new IllegalArgumentException("Unknown migration event");
        }
        var values=new java.util.LinkedHashMap<>(payload); values.put("runId",aggregateId); payload=Map.copyOf(values);
    }
    public String aggregateType() { return "MigrationRun"; }
    public String actorAccountId() { return null; }
    public String impersonatedMemberId() { return null; }
    public Origin origin() { return Origin.SYSTEM; }
}
