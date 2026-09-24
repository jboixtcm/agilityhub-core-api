package com.agilityhub.core.clubs.training.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Read-only envelope for the events S09 consumes from other contexts (S06 classes, weeks and ring blocks, S07
 * activities, S02 parameters and modules, S05 catalogs, S03 dogs, S15 week opening): their classes live outside
 * `application`, so the outbox JSON is read into this shape. Envelopes carry their type as `kind` or `type`.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record TrainingForeignEvent(String kind, String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public TrainingForeignEvent { payload = payload == null ? Map.of() : payload; }
    @Override public String type() { return type != null ? type : kind; }
}
