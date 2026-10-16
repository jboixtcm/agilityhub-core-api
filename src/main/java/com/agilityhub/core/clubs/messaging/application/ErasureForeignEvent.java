package com.agilityhub.core.clubs.messaging.application;

import java.time.Instant;
import java.util.Map;

/** Read-only envelope of S14's `AccountErasureRequested` as messaging consumes it (not a DomainEvent: it cannot be published). */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record ErasureForeignEvent(String kind, String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload) {
    public ErasureForeignEvent { payload = payload == null ? Map.of() : payload; }
}
