package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.domain.*;
import com.agilityhub.core.clubs.messaging.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authenticated provider events are correlated to a stored target before any privileged state change. */
@Service
public class BrevoWebhookService {
    private static final Set<String> EVENTS = Set.of("delivered", "hard_bounce", "invalid_email", "blocked", "spam", "unsubscribed", "error");
    private static final Set<String> PERMANENT = Set.of("hard_bounce", "invalid_email", "blocked", "spam", "unsubscribed");
    private final NotificationRepository notifications;
    private final BrevoWebhookRepository receipts;
    private final NotificationAccounts accounts;
    private final EventPublisher events;
    private final MemberDirectoryPort members;
    private final MemberContactsWriterPort writer;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper mapper;

    public BrevoWebhookService(NotificationRepository notifications, BrevoWebhookRepository receipts, NotificationAccounts accounts,
            EventPublisher events, MemberDirectoryPort members, MemberContactsWriterPort writer,
            PlatformTransactionManager manager, Clock clock, ObjectMapper mapper) {
        this.notifications = notifications; this.receipts = receipts; this.accounts = accounts; this.events = events;
        this.members = members; this.writer = writer; this.transactions = new TransactionTemplate(manager); this.clock = clock; this.mapper = mapper;
    }
    public void accept(String event, String messageId, String email, String custom) {
        // Brevo's `id` identifies the webhook subscription, not an event. Temporary deferrals/soft bounces may recover.
        if (!EVENTS.contains(event == null ? "" : event) || messageId == null || messageId.isBlank() || messageId.length() > 512
                || email == null || email.isBlank() || email.length() > 320 || custom == null || custom.length() > 4096) { return; }
        String notificationId; String clubId;
        try {
            var correlation = mapper.readTree(Base64.getUrlDecoder().decode(custom));
            if (correlation == null || !correlation.isObject() || !correlation.path("notificationId").isTextual()
                    || correlation.path("notificationId").asText().isBlank()) { return; }
            notificationId = correlation.path("notificationId").asText();
            if (notificationId.length() > 256 || correlation.has("clubId") && (!correlation.path("clubId").isTextual() || correlation.path("clubId").asText().isBlank())) { return; }
            clubId = correlation.has("clubId") ? correlation.path("clubId").asText() : null;
            if (clubId != null && clubId.length() > 256) { return; }
        } catch (IOException | IllegalArgumentException invalid) { return; }
        String reference = BrevoEmailSender.reference(messageId);
        String receipt = BrevoWebhookRepository.digest(reference + "\n" + email.strip().toLowerCase(Locale.ROOT) + "\n" + event);
        if (clubId == null && TenantContext.current() != null) { return; }
        try (var scope = clubId == null ? null : TenantContext.open(clubId)) {
            try {
                transactions.executeWithoutResult(tx -> {
                    if (receipts.findById(receipt).isPresent()) { return; }
                    var row = (clubId == null ? notifications.findSystem(notificationId) : notifications.findById(notificationId)).orElse(null);
                    if (row == null) { return; }
                    var delivery = row.deliveries() == null ? null : row.deliveries().stream().filter(item -> item.channel() == NotificationChannel.EMAIL
                            && item.target() != null && item.target().equalsIgnoreCase(email)).findFirst().orElse(null);
                    String target = row.channel() == null ? delivery == null ? null : delivery.target() : row.recipientEmail();
                    String storedReference = row.channel() == null ? delivery == null ? null : delivery.providerRef() : row.providerMessageId();
                    if (target == null || !target.equalsIgnoreCase(email) || storedReference != null && !reference.equals(storedReference)) { return; }
                    receipts.insert(new BrevoWebhookReceipt(receipt, clubId, notificationId, clock.instant()));
                    if (PERMANENT.contains(event)) { receipts.suppress(target, event, clock.instant()); }
                    if (!event.equals("unsubscribed")) { outcome(row, target, event); }
                    suppress(row, target, event);
                });
            } catch (DuplicateKeyException replay) {
                if (receipts.findById(receipt).isEmpty()) { throw replay; }
            }
        }
    }
    private void outcome(Notification row, String target, String event) {
        boolean failed = !event.equals("delivered");
        String error = failed ? "Brevo " + event : null;
        boolean changed = row.channel() != null
                ? notifications.delivery(row.id(), failed ? Notification.Status.FAILED : Notification.Status.DELIVERED, error)
                : notifications.emailOutcome(row.id(), target, new NotificationRepository.DeliveryStatusChange(
                        failed ? DeliveryStatus.FAILED : DeliveryStatus.DELIVERED, error, clock.instant()));
        if (changed && failed) { events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationFailed, row.clubId(), row.id(),
                clock.instant(), DomainEvent.Origin.WEBHOOK, NotificationChannel.EMAIL)); }
    }
    private void suppress(Notification row, String target, String event) {
        if (!PERMANENT.contains(event)) { return; }
        var ids = new LinkedHashSet<String>();
        if (row.clubId() != null) {
            if (row.recipient() != null && row.recipient().memberId() != null) { ids.add(row.recipient().memberId()); }
            else { ids.addAll(members.membersWithEmail(target)); }
            for (String memberId : ids) {
                if (event.equals("unsubscribed")) {
                    if (writer.unsubscribeClubNews(memberId, clock.instant())) { events.publish(new MessagingEvent(MessagingEvent.Kind.EmailUnsubscribed,
                            row.clubId(), memberId, clock.instant(), Map.of("memberId", memberId), null, null, DomainEvent.Origin.WEBHOOK)); }
                } else if (writer.markEmailBounced(memberId, target)) {
                    events.publish(new MessagingEvent(MessagingEvent.Kind.EmailBounced, row.clubId(), memberId, clock.instant(),
                            Map.of("memberId", memberId, "email", target, "type", event.toUpperCase(Locale.ROOT)), null, null, DomainEvent.Origin.WEBHOOK));
                }
            }
        }
        // Unsubscription is provider suppression/preferences, never mislabeled as an account bounce or complaint.
        if (event.equals("unsubscribed")) { return; }
        String accountId = row.channel() != null ? row.accountId() : row.recipient() == null ? null : row.recipient().accountId();
        var account = accountId == null ? null : accounts.find(accountId).orElse(null);
        if (account != null && account.email() != null && account.email().equalsIgnoreCase(target)) {
            accounts.markEmailStatus(accountId, account.email(), event.equals("spam") ? NotificationAccounts.EmailStatus.COMPLAINED : NotificationAccounts.EmailStatus.BOUNCED);
        }
    }
}
