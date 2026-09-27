package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Read-only envelope of any outbox event the notification engine consumes: the stored JSON of every context's event class
 * (the type as `kind` or `type`). It is not a {@link DomainEvent}, so it can never be published by mistake (E5-T09).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationEventEnvelope(String kind, String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) {
    public NotificationEventEnvelope { payload = payload == null ? Map.of() : payload; }
    public String type() { return type != null ? type : kind; }
}
