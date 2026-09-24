package com.agilityhub.core.clubs.common.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Read-only envelope for the events clubs.common consumes from other contexts (S06 `WeekValidated` for the deferred
 * N-33): their classes live outside `application`, so the outbox JSON is read into this shape (type as `kind` or `type`).
 * It is not a {@link DomainEvent}, so it can never be published by mistake (E5-T09).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record ForeignEvent(String kind, String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) {
    public ForeignEvent { payload = payload == null ? Map.of() : payload; }
    public String type() { return type != null ? type : kind; }
}
