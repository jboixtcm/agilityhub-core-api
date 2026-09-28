package com.agilityhub.core.clubs.followup.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * Read-only envelope of the S03 events S10 follow-up consumes (`MemberNoteChanged{dogId, memberId, author{accountId,
 * displayName, gender}}`, `DogTransferred{dogId, fromMemberId, toMemberId}`): their class lives in `clubs.census`, which
 * follow-up never imports, so the outbox JSON is read into this shape. Not a {@link DomainEvent}: it can never be published
 * (E5-T09).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CensusForeignEvent(String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) {
    public CensusForeignEvent { payload = payload == null ? Map.of() : payload; }
    public String text(String key) { return payload.get(key) == null ? null : payload.get(key).toString(); }
    /** A nested object of the payload (`author`), or null when absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> object(String key) { return payload.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : null; }
}
