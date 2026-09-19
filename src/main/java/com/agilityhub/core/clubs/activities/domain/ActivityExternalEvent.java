package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** Decoder for catalog events owned by other contexts; no dependency on their domain types. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public record ActivityExternalEvent(String type,String clubId,String aggregateType,String aggregateId,Instant occurredAt,
        Map<String,Object> payload,String actorAccountId,String impersonatedMemberId,Origin origin) implements DomainEvent { }
