package com.agilityhub.core.clubs.census.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** Closed catalog events emitted by S03 and decoded by its projection consumers. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CensusEvent(String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String,Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent { }
