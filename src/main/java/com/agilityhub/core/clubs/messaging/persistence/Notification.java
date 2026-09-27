package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S11 §3 `Notification` (`notifications`): the log of every notice (RF-NOT-03) and the source of feed 11. Rendered texts
 * are frozen (changing the template never touches the history); `deliveries[]` holds one delivery per channel and
 * destination (S11 §5 statuses); `dedupKey` is unique per club (R-11-09). `SYSTEM` rows keep `clubId = null`. Nothing is
 * deleted (R-11-10; S14 R-14-15 pseudonymises `recipient` on erasure).
 *
 * <p><b>E1–E6 compatibility block</b> (`accountId` … `variant`): the flat fields of the E1 row, written only by the
 * `SystemNotificationService`/`NotificationFanout` helpers that E3–E6 still call. Each of their rows is one document with a
 * single delivery, `dedupKey` = the deterministic id they pass, and these fields mirror that delivery (`NotificationRepository`
 * moves both together). E7-T02 replaces the helpers and stops writing the block; `messaging:migrate-notifications` converts
 * the rows written before E7-T01 (no `deliveries`). `variant` names the copy of the code a row renders (`notif.{code}.{variant}.*`,
 * e.g. the admins' N-01; E3-T08), `null` for the default copy.</p>
 */
@Document("notifications")
public record Notification(@Id String id, String clubId, String code, NotificationCategory category, String templateId, Long templateVersion,
        String eventId, String eventType, String dedupKey, NotificationAudience audience, Recipient recipient, String locale, Subject subject,
        TemplateIcon icon, TemplateColor color, String title, String body, String smsBody, Action action, List<Delivery> deliveries,
        Instant readAt, Instant createdAt,
        String accountId, String channel, Status status, String providerMessageId, Instant sentAt, String error, String recipientEmail,
        String variant) implements TenantEntity {
    /** `APPLICANT` rows carry only `email` (R-11-02). */
    public record Recipient(String accountId, String memberId, String instructorId, String email, String displayName) { }
    /** The action parameters and the log filter (S11 §3). */
    public record Subject(String dogId, String bookingId, String classSessionId, String waitlistEntryId, String trainingBookingId, String invoiceId,
            String activityId, String taskId, String memberId) { }
    public record Action(NotificationActionType type, Map<String, String> params) { }
    /** One delivery per channel and destination (each phone, each push subscription); `APP` is born `DELIVERED` in the engine. */
    public record Delivery(NotificationChannel channel, String target, DeliveryStatus status, int attempts, Instant nextAttemptAt, String providerRef,
            String lastError, Instant sentAt, Instant deliveredAt, Instant failedAt) { }
    /** The compatibility block's status, the same values as {@link DeliveryStatus} (catalog rule 7 + the three S11 §13 proposals). */
    public enum Status { QUEUED, SENT, FAILED, DELIVERED, SKIPPED_BY_PREFERENCE, SKIPPED_MODULE_OFF, SKIPPED_NO_CONTACT, SKIPPED_CAP, SKIPPED_STALE }

    /**
     * The E1–E6 row: one document with one delivery of `channel` to the account (`APP`), the address (`EMAIL`) or no known
     * destination yet (an `SMS`/`PUSH` intent: its phones stay in the row's `recipientPhones`, the push subscriptions come
     * with E7-T02). Category, icon and colour come from the catalog; the audience only where the row itself tells it
     * ({@link #legacyAudience}); the texts stay unrendered (the helpers store allow-listed `variables`).
     */
    public Notification(String id, String clubId, String accountId, String code, String channel, Status status, String providerMessageId,
            Instant sentAt, String error, String recipientEmail, String locale, Instant createdAt, String variant) {
        this(id, clubId, code, NotificationCatalog.byCode(code).map(NotificationSpec::category).orElse(null), null, null, null, null, id,
                legacyAudience(code, accountId, variant), new Recipient(accountId, null, null, recipientEmail, null), locale, null,
                NotificationCatalog.byCode(code).map(NotificationSpec::icon).orElse(TemplateIcon.bell),
                NotificationCatalog.byCode(code).map(NotificationSpec::color).orElse(TemplateColor.NEUTRAL), null, null, null, null,
                List.of(new Delivery(NotificationChannel.valueOf(channel), target(channel, accountId, recipientEmail), DeliveryStatus.valueOf(status.name()),
                        0, null, providerMessageId, error, sentAt, status == Status.DELIVERED ? sentAt : null, status == Status.FAILED ? createdAt : null)),
                null, createdAt, accountId, channel, status, providerMessageId, sentAt, error, recipientEmail, variant);
    }

    /**
     * The audience a helper row states by itself: the admins' copy (`variant = admin`) → `ADMINS`; an address without an
     * account when the code has an `APPLICANT` audience → `APPLICANT`; a code with a single audience → that one. Otherwise
     * `null`: the helper does not know it (e.g. N-08a to a member or to the class's instructor), E7-T02's consumer will.
     */
    public static NotificationAudience legacyAudience(String code, String accountId, String variant) {
        if ("admin".equals(variant)) { return NotificationAudience.ADMINS; }
        var spec = NotificationCatalog.byCode(code).orElse(null);
        if (spec == null) { return null; }
        if (accountId == null && spec.audiences().contains(NotificationAudience.APPLICANT)) { return NotificationAudience.APPLICANT; }
        return spec.audiences().size() == 1 ? spec.audiences().getFirst() : null;
    }
    static String target(String channel, String accountId, String email) {
        return switch (channel) { case "APP" -> accountId; case "EMAIL" -> email; default -> null; };
    }

    /** The only delivery of a compatibility row (`null` on a row written before E7-T01). */
    public Delivery delivery() { return deliveries == null || deliveries.isEmpty() ? null : deliveries.getFirst(); }

    @Override public String toString() { return "Notification[id=" + id + ", code=" + code + ", status=" + status + "]"; }
}
