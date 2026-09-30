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
 * <p>The engine (E7-T02, `NotificationEngine`) writes one document per occurrence, code, audience and recipient (and dog),
 * with every delivery, the rendered texts and `variables`: the formatted values they were rendered with (the log's detail;
 * credential links are never stored). A delivery the dispatcher is sending carries a lease (`claimToken`, `claimedUntil`),
 * so two instances never send it twice (T-11-30).</p>
 *
 * <p><b>E1 compatibility block</b> (`accountId` … `variant`): the flat fields of the E1 row, written only by the SYSTEM
 * e-mail path (`SystemNotificationService`: N-25, N-26, N-27, N-39, N-43, N-52, N-53). Each of its rows is one document with
 * a single delivery, `dedupKey` = its id, and these fields mirror that delivery (`NotificationRepository` moves both
 * together); engine documents leave them `null`. `messaging:migrate-notifications` converts the rows written before E7-T01
 * (no `deliveries`); until then they load as they are, their bare-string `action` mapped by {@link LegacyNotificationReader}.
 * `variant` names another copy of a code (`notif.{code}.{variant}.*`), `null` for the default copy.</p>
 */
@Document("notifications")
public record Notification(@Id String id, String clubId, String code, NotificationCategory category, String templateId, Long templateVersion,
        String eventId, String eventType, String dedupKey, NotificationAudience audience, Recipient recipient, String locale, Subject subject,
        TemplateIcon icon, TemplateColor color, String title, String body, String smsBody, Action action, List<Delivery> deliveries,
        Instant readAt, Instant createdAt,
        String accountId, String channel, Status status, String providerMessageId, Instant sentAt, String error, String recipientEmail,
        String variant, Map<String, Object> variables) implements TenantEntity {
    /** The E7-T01 S11 document, without rendered `variables`. */
    public Notification(String id, String clubId, String code, NotificationCategory category, String templateId, Long templateVersion,
            String eventId, String eventType, String dedupKey, NotificationAudience audience, Recipient recipient, String locale, Subject subject,
            TemplateIcon icon, TemplateColor color, String title, String body, String smsBody, Action action, List<Delivery> deliveries,
            Instant readAt, Instant createdAt, String accountId, String channel, Status status, String providerMessageId, Instant sentAt,
            String error, String recipientEmail, String variant) {
        this(id, clubId, code, category, templateId, templateVersion, eventId, eventType, dedupKey, audience, recipient, locale, subject, icon, color,
                title, body, smsBody, action, deliveries, readAt, createdAt, accountId, channel, status, providerMessageId, sentAt, error,
                recipientEmail, variant, null);
    }
    /** `APPLICANT` rows carry only `email` (R-11-02). */
    public record Recipient(String accountId, String memberId, String instructorId, String email, String displayName) { }
    /** The action parameters and the log filter (S11 §3). */
    public record Subject(String dogId, String bookingId, String classSessionId, String waitlistEntryId, String trainingBookingId, String invoiceId,
            String activityId, String taskId, String memberId) { }
    public record Action(NotificationActionType type, Map<String, String> params) { }
    /**
     * One delivery per channel and destination (each phone, each push subscription); `APP` is born `DELIVERED` in the engine.
     * `claimToken`/`claimedUntil` are the dispatcher's lease while it sends (never read by the API). `acceptedAt` marks an
     * attempt the provider accepted whose settlement is still pending (E7-T05): written with `providerRef` right after the
     * provider's answer, removed by the settlement; a delivery that carries it is never sent again, only settled.
     */
    public record Delivery(NotificationChannel channel, String target, DeliveryStatus status, int attempts, Instant nextAttemptAt, String providerRef,
            String lastError, Instant sentAt, Instant deliveredAt, Instant failedAt, String claimToken, Instant claimedUntil, Instant acceptedAt) {
        public Delivery(NotificationChannel channel, String target, DeliveryStatus status, int attempts, Instant nextAttemptAt, String providerRef,
                String lastError, Instant sentAt, Instant deliveredAt, Instant failedAt) {
            this(channel, target, status, attempts, nextAttemptAt, providerRef, lastError, sentAt, deliveredAt, failedAt, null, null, null);
        }
    }
    /** The compatibility block's status, the same values as {@link DeliveryStatus}. */
    public enum Status { QUEUED, SENT, FAILED, DELIVERED, SKIPPED_BY_PREFERENCE, SKIPPED_MODULE_OFF, SKIPPED_NO_CONTACT, SKIPPED_CAP, SKIPPED_STALE, SKIPPED_NOT_ALLOWED }

    /**
     * The E1 row: one document with one delivery of `channel` to the account (`APP`), the address (`EMAIL`) or no known
     * destination (an `SMS`/`PUSH` intent a row written before E7-T02 may still hold). Category, icon and colour come from
     * the catalog; the audience only where the row itself tells it ({@link #legacyAudience}).
     */
    public Notification(String id, String clubId, String accountId, String code, String channel, Status status, String providerMessageId,
            Instant sentAt, String error, String recipientEmail, String locale, Instant createdAt, String variant) {
        this(id, clubId, code, NotificationCatalog.byCode(code).map(NotificationSpec::category).orElse(null), null, null, null, null, id,
                legacyAudience(code, accountId, variant), new Recipient(accountId, null, null, recipientEmail, null), locale, null,
                NotificationCatalog.byCode(code).map(NotificationSpec::icon).orElse(TemplateIcon.bell),
                NotificationCatalog.byCode(code).map(NotificationSpec::color).orElse(TemplateColor.NEUTRAL), null, null, null, null,
                List.of(new Delivery(NotificationChannel.valueOf(channel), target(channel, accountId, recipientEmail), DeliveryStatus.valueOf(status.name()),
                        0, null, providerMessageId, error, sentAt, status == Status.DELIVERED ? sentAt : null, status == Status.FAILED ? createdAt : null)),
                null, createdAt, accountId, channel, status, providerMessageId, sentAt, error, recipientEmail, variant, null);
    }

    /**
     * The audience a helper row states by itself: the admins' copy (`variant = admin`) → `ADMINS`; an address without an
     * account when the code has an `APPLICANT` audience → `APPLICANT`; a code with a single audience → that one. Otherwise
     * `null`: the row does not tell it (e.g. N-08a to a member or to the class's instructor, written before E7-T02).
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
