package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.domain.NotificationEvent;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.SendGridWebhookReceipt;
import com.agilityhub.core.clubs.messaging.persistence.SendGridWebhookReceiptRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public class SendGridWebhookService {
    private final NotificationRepository notifications;
    private final SendGridWebhookReceiptRepository receipts;
    private final NotificationAccounts accounts;
    private final EventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public SendGridWebhookService(NotificationRepository notifications, SendGridWebhookReceiptRepository receipts,
            NotificationAccounts accounts, EventPublisher events, TransactionTemplate transactions, Clock clock) {
        this.notifications = notifications; this.receipts = receipts; this.accounts = accounts;
        this.events = events; this.transactions = transactions; this.clock = clock;
    }
    public void accept(String eventId, String event, String notificationId, String clubId, String email) {
        if (eventId == null || eventId.isBlank() || notificationId == null || email == null || event == null
                || !Set.of("bounce", "dropped", "spamreport", "delivered").contains(event)) { return; }
        if (clubId != null && clubId.isBlank()) { return; }
        if (clubId == null) { process(eventId, event, notificationId, null, email); }
        else { try (var scope = TenantContext.open(clubId)) { process(eventId, event, notificationId, clubId, email); } }
    }
    private void process(String eventId, String event, String notificationId, String clubId, String email) {
        try {
            transactions.executeWithoutResult(tx -> {
                if (receipts.findById(eventId).isPresent()) { return; }
                var notification = (clubId == null ? notifications.findSystem(notificationId) : notifications.findById(notificationId)).orElse(null);
                if (notification == null || !Objects.equals(notification.recipientEmail(), email)) { return; }
                receipts.insert(new SendGridWebhookReceipt(eventId, clubId, notificationId, clock.instant()));
                boolean failed = !event.equals("delivered");
                if (failed) {
                    accounts.markEmailStatus(notification.accountId(), email, event.equals("spamreport")
                            ? NotificationAccounts.EmailStatus.COMPLAINED : NotificationAccounts.EmailStatus.BOUNCED);
                }
                if (notifications.delivery(notificationId, failed ? Notification.Status.FAILED : Notification.Status.DELIVERED,
                        failed ? "SendGrid " + event : null) && failed) {
                    events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationFailed, clubId, notificationId, clock.instant(), com.agilityhub.core.shared.domain.DomainEvent.Origin.WEBHOOK));
                }
            });
        } catch (DuplicateKeyException concurrentReplay) {
            if (receipts.findById(eventId).isEmpty()) { throw concurrentReplay; }
        }
    }
}
