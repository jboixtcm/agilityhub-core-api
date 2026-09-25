package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * R-04-20 (E3-T12 round 2): the recipient-cap decision of one signup notification of one event, `_id` = `eventId:code`.
 * It is written when the decision is taken, outside the delivery's transaction, so every retry of that event reuses it,
 * after a restart too: an admitted event keeps its right to send and a refused one stays refused. It holds no recipient
 * data (the cap's buckets hold the hashed address). The TTL on `expiresAt` removes it once the outbox can no longer retry.
 */
@Document("signup_notification_admissions")
public record SignupNotificationAdmission(@Id String id, String clubId, String eventId, String notificationCode, boolean admitted,
        Instant decidedAt, Instant expiresAt) implements TenantEntity {
    public static String id(String eventId, String notificationCode) { return eventId + ":" + notificationCode; }
}
