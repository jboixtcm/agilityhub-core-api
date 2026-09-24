package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Read-only envelope for the events S08 consumes from other contexts (S06 `ClassSessionUpdated` /
 * `ClassCancelledByClub`, S03 `DogLevelChanged` / `BookingBlockChanged`, S12 `UpfrontPayment*`): their classes live
 * outside `application`, so the outbox JSON is read into this shape. Envelopes carry their type as `kind` or `type`.
 * It is not a {@link DomainEvent}, so it can never be published by mistake (E5-T09).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record ForeignEvent(String kind, String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) {
    public ForeignEvent { payload = payload == null ? Map.of() : payload; }
    public String type() { return type != null ? type : kind; }
}
