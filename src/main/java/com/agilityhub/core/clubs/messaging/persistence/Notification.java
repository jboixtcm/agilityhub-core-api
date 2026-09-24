package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * E1 SYSTEM log; deliberately excludes rendered magic links and credentials. `variant` names the copy of the catalog code
 * that the row renders (`notif.{code}.{variant}.*`, e.g. the admins' N-01; E3-T08), `null` for the default copy.
 */
@Document("notifications")
public record Notification(@Id String id, String clubId, String accountId, String code, String channel,
                           Status status, String providerMessageId, Instant sentAt, String error,
                           String recipientEmail, String locale, Instant createdAt, String variant) implements TenantEntity {
    public enum Status { QUEUED, SENT, FAILED, DELIVERED, SKIPPED_BY_PREFERENCE, SKIPPED_MODULE_OFF }
    @Override public String toString() { return "Notification[id=" + id + ", status=" + status + "]"; }
}
