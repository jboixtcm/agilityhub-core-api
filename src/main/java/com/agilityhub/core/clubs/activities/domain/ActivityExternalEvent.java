package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Decoder for catalog events owned by other contexts; no dependency on their domain types. It is not a
 * {@link DomainEvent}, so it can never be published by mistake (E5-T14, like the `*ForeignEvent`s since E5-T09).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public record ActivityExternalEvent(String type,String clubId,String aggregateType,String aggregateId,Instant occurredAt,
        Map<String,Object> payload,String actorAccountId,String impersonatedMemberId,DomainEvent.Origin origin) {
    public ActivityExternalEvent { payload = payload == null ? Map.of() : payload; }
}
