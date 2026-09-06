package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** E1 SYSTEM log; deliberately excludes rendered magic links and credentials. */
@Document("notifications")
public record Notification(@Id String id, String clubId, String accountId, String code, String channel,
                           Status status, String providerMessageId, Instant sentAt, String error,
                           String recipientEmail, String locale, Instant createdAt) implements TenantEntity {
    public enum Status { QUEUED, SENT, FAILED, DELIVERED }
    @Override public String toString() { return "Notification[id=" + id + ", status=" + status + "]"; }
}
