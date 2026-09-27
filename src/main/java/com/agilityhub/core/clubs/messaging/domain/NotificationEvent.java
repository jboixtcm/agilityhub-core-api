package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** `NotificationQueued` / `NotificationSent` / `NotificationFailed{notificationId, channel}` (CATALEG_ESDEVENIMENTS «Comunicacions»). */
public record NotificationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, Origin origin, NotificationChannel channel) implements DomainEvent {
    public NotificationEvent { Objects.requireNonNull(channel); }
    /** The E1 e-mail path (SYSTEM origin). */
    public NotificationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt) {
        this(kind, clubId, aggregateId, occurredAt, Origin.SYSTEM, NotificationChannel.EMAIL);
    }
    /** An e-mail delivery with another origin (the SendGrid webhook). */
    public NotificationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, Origin origin) {
        this(kind, clubId, aggregateId, occurredAt, origin, NotificationChannel.EMAIL);
    }
    public NotificationEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt, NotificationChannel channel) {
        this(kind, clubId, aggregateId, occurredAt, Origin.SYSTEM, channel);
    }
    public enum Kind { NotificationQueued, NotificationSent, NotificationFailed }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return "Notification"; }
    @Override public Map<String, Object> payload() { return Map.of("notificationId", aggregateId, "channel", channel.name()); }
    @Override public String actorAccountId() { return null; }
    @Override public String impersonatedMemberId() { return null; }
}
