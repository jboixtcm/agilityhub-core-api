package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationEvent;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.SendGridWebhookReceipt;
import com.agilityhub.core.clubs.messaging.persistence.SendGridWebhookReceiptRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed SendGrid Event Webhook (E1-T03, extended by E7-T02 for S11 R-11-08), idempotent per `sg_event_id`: the event is
 * matched to the stored notification (`custom_args.notificationId`, its club) and to the e-mail it was sent to.
 * <ul>
 * <li>A SYSTEM row (the E1 path): `delivered` → DELIVERED; `bounce`/`dropped`/`spamreport` → FAILED and the account's
 * `emailStatus` (BOUNCED or COMPLAINED, with its `ACCOUNT_EMAIL_STATUS_CHANGED` audit).</li>
 * <li>An engine notification: its EMAIL delivery to that address → DELIVERED (`delivered`) or FAILED (`bounce`, `dropped`,
 * `spamreport`, with `NotificationFailed`); `deferred` is not accepted. A hard failure (a `bounce` of type `bounce`, a
 * `dropped`, a `spamreport`; a `blocked` bounce is soft) marks `Member.contactEmails[address].bounced` of the members with
 * that address and publishes `EmailBounced{memberId, email, type}` (N-51 to the admins); when the address is also the
 * account's login address the E1 account mark is kept too — the two marks coexist: R-11-08 per contact address, decision
 * E12 per account.</li>
 * </ul>
 */
public class SendGridWebhookService {
    private final NotificationRepository notifications;
    private final SendGridWebhookReceiptRepository receipts;
    private final NotificationAccounts accounts;
    private final EventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MemberDirectoryPort members;
    private final MemberContactsWriterPort writer;
    public SendGridWebhookService(NotificationRepository notifications, SendGridWebhookReceiptRepository receipts,
            NotificationAccounts accounts, EventPublisher events, TransactionTemplate transactions, Clock clock,
            MemberDirectoryPort members, MemberContactsWriterPort writer) {
        this.notifications = notifications; this.receipts = receipts; this.accounts = accounts;
        this.events = events; this.transactions = transactions; this.clock = clock; this.members = members; this.writer = writer;
    }
    public void accept(String eventId, String event, String notificationId, String clubId, String email) {
        accept(eventId, event, notificationId, clubId, email, null);
    }
    /** @param type SendGrid's bounce `type` (`bounce` = hard, `blocked` = soft); null for the other events */
    public void accept(String eventId, String event, String notificationId, String clubId, String email, String type) {
        if (eventId == null || eventId.isBlank() || notificationId == null || email == null || event == null
                || !Set.of("bounce", "dropped", "spamreport", "delivered").contains(event)) { return; }
        if (clubId != null && clubId.isBlank()) { return; }
        if (clubId == null) { process(eventId, event, notificationId, null, email, type); }
        else { try (var scope = TenantContext.open(clubId)) { process(eventId, event, notificationId, clubId, email, type); } }
    }
    private void process(String eventId, String event, String notificationId, String clubId, String email, String type) {
        try {
            transactions.executeWithoutResult(tx -> {
                if (receipts.findById(eventId).isPresent()) { return; }
                var notification = (clubId == null ? notifications.findSystem(notificationId) : notifications.findById(notificationId)).orElse(null);
                if (notification == null) { return; }
                if (notification.channel() != null) { system(eventId, event, notification, clubId, email); }
                else { engine(eventId, event, notification, clubId, email, type); }
            });
        } catch (DuplicateKeyException concurrentReplay) {
            if (receipts.findById(eventId).isEmpty()) { throw concurrentReplay; }
        }
    }
    /** The E1 row: one EMAIL delivery mirrored by the flat fields. */
    private void system(String eventId, String event, Notification notification, String clubId, String email) {
        if (!Objects.equals(notification.recipientEmail(), email)) { return; }
        receipts.insert(new SendGridWebhookReceipt(eventId, clubId, notification.id(), clock.instant()));
        boolean failed = !event.equals("delivered");
        if (failed) { accounts.markEmailStatus(notification.accountId(), email, status(event)); }
        if (notifications.delivery(notification.id(), failed ? Notification.Status.FAILED : Notification.Status.DELIVERED,
                failed ? "SendGrid " + event : null) && failed) {
            events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationFailed, clubId, notification.id(), clock.instant(), DomainEvent.Origin.WEBHOOK));
        }
    }
    /** An engine notification: the EMAIL delivery to that address (R-11-08). */
    private void engine(String eventId, String event, Notification notification, String clubId, String email, String type) {
        var target = notification.deliveries() == null ? null : notification.deliveries().stream()
                .filter(d -> d.channel() == NotificationChannel.EMAIL && d.target() != null && d.target().equalsIgnoreCase(email))
                .map(Notification.Delivery::target).findFirst().orElse(null);
        if (target == null || clubId == null) { return; }
        receipts.insert(new SendGridWebhookReceipt(eventId, clubId, notification.id(), clock.instant()));
        boolean failed = !event.equals("delivered");
        var change = new NotificationRepository.DeliveryStatusChange(failed ? DeliveryStatus.FAILED : DeliveryStatus.DELIVERED, failed ? "SendGrid " + event : null,
                clock.instant());
        if (notifications.emailOutcome(notification.id(), target, change) && failed) {
            events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationFailed, clubId, notification.id(), clock.instant(), DomainEvent.Origin.WEBHOOK,
                    NotificationChannel.EMAIL));
        }
        boolean hard = event.equals("dropped") || event.equals("spamreport") || event.equals("bounce") && (type == null || "bounce".equalsIgnoreCase(type));
        if (!hard) { return; }
        var memberIds = new LinkedHashSet<String>();
        if (notification.recipient() != null && notification.recipient().memberId() != null) { memberIds.add(notification.recipient().memberId()); }
        else { memberIds.addAll(members.membersWithEmail(target)); }
        for (String memberId : memberIds) {
            if (writer.markEmailBounced(memberId, target)) {
                events.publish(new MessagingEvent(MessagingEvent.Kind.EmailBounced, clubId, memberId, clock.instant(),
                        Map.of("memberId", memberId, "email", target, "type", event.toUpperCase(Locale.ROOT)), null, null, DomainEvent.Origin.WEBHOOK));
            }
        }
        // Decision E12 (E1-T03): the login address of the account keeps its own mark and audit.
        String accountId = notification.recipient() == null ? null : notification.recipient().accountId();
        var account = accountId == null ? null : accounts.find(accountId).orElse(null);
        if (account != null && account.email() != null && account.email().equalsIgnoreCase(target)) { accounts.markEmailStatus(accountId, account.email(), status(event)); }
    }
    private static NotificationAccounts.EmailStatus status(String event) {
        return event.equals("spamreport") ? NotificationAccounts.EmailStatus.COMPLAINED : NotificationAccounts.EmailStatus.BOUNCED;
    }
}
