package com.agilityhub.core.clubs.messaging.application.ports;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The domain event the engine is turning into notifications, as read from the outbox (`domain_events`): the envelope and
 * the catalog payload. Owning contexts read their own payload keys through the small helpers.
 */
public record NotificationTrigger(String eventId, String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin) {
    public NotificationTrigger {
        Objects.requireNonNull(eventId); Objects.requireNonNull(type);
        payload = payload == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }
    public String text(String key) { Object value = payload.get(key); return value == null ? null : value.toString(); }
    public boolean flag(String key) { return Boolean.TRUE.equals(payload.get(key)) || "true".equals(text(key)); }
    public int number(String key, int fallback) { return payload.get(key) instanceof Number value ? value.intValue() : fallback; }
    /** A list of ids (`entryIds[]`, `waitlistIds[]`, `attendanceIds[]` …); absent → empty. */
    public List<String> ids(String key) {
        return payload.get(key) instanceof Collection<?> values ? values.stream().filter(Objects::nonNull).map(Object::toString).toList() : List.of();
    }
    /** A list of objects (`affected[{bookingId, memberId, dogId}]` …); absent → empty. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rows(String key) {
        return payload.get(key) instanceof Collection<?> values
                ? values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList() : List.of();
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) { return payload.get(key) instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of(); }
}
