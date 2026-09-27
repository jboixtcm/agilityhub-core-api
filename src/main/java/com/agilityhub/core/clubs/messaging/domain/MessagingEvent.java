package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S11 §7 events (CATALEG_ESDEVENIMENTS «Catàlegs», «Comunicacions» and Annex A), published through the outbox in the
 * writing transaction: `MessageTemplateChanged{id, diff}` · `AnnouncementSent{templateId, batchId, recipientCount, filters}` ·
 * `PushSubscribed/PushUnsubscribed{accountId, endpoint}` (`endpoint` = the endpoint's SHA-256, never the URL) ·
 * `EmailBounced{memberId, email, type}` · `SmsCapReached{month, cap}` · `NotificationPreferencesChanged{memberId, diff,
 * byAccountId}` · `EmailUnsubscribed{memberId}`. `NotificationQueued/Sent/Failed` keep their own {@link NotificationEvent}.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record MessagingEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public MessagingEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        MessageTemplateChanged("MessageTemplate"), AnnouncementSent("Announcement"), PushSubscribed("PushSubscription"),
        PushUnsubscribed("PushSubscription"), EmailBounced("Member"), SmsCapReached("Club"), NotificationPreferencesChanged("Member"),
        EmailUnsubscribed("Member");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
