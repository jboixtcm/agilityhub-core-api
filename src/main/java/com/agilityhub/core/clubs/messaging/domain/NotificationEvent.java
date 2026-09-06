package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record NotificationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, Origin origin) implements DomainEvent {
    public NotificationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt) {
        this(kind, clubId, aggregateId, occurredAt, Origin.SYSTEM);
    }
    public enum Kind { NotificationQueued, NotificationSent, NotificationFailed }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return "Notification"; }
    @Override public Map<String, Object> payload() { return Map.of("notificationId", aggregateId, "channel", "EMAIL"); }
    @Override public String actorAccountId() { return null; }
    @Override public String impersonatedMemberId() { return null; }
}
