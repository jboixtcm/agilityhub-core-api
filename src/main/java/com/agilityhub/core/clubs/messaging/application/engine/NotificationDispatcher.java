package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.integrations.PushPayload;
import com.agilityhub.core.clubs.messaging.application.integrations.PushResult;
import com.agilityhub.core.clubs.messaging.application.integrations.PushSender;
import com.agilityhub.core.clubs.messaging.application.integrations.SendResult;
import com.agilityhub.core.clubs.messaging.application.integrations.SmsMessage;
import com.agilityhub.core.clubs.messaging.application.integrations.SmsSender;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.domain.ChannelResolver;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationEvent;
import com.agilityhub.core.clubs.messaging.domain.RetryPolicy;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ClubSmsUsage;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S11 §5/§6 `dispatch` and R-11-09: sends the `QUEUED` deliveries whose `nextAttemptAt ≤ now`, triggered inline right after
 * the engine's commit and by a poll every 5 s. Each delivery is claimed with one atomic `findAndModify` that leases it
 * (`claimToken`, `claimedUntil`), so two instances never send it twice (T-11-30); the outcome is written back by token in a
 * short transaction together with its event, and the network call itself never runs inside a Mongo transaction
 * (decision E13).
 *
 * <ul>
 * <li>accepted → `SENT` (`providerRef`, `sentAt`, `attempts++`) + `NotificationSent`;</li>
 * <li>retryable → back to `QUEUED`, `attempts++`, `nextAttemptAt` = now + 1, 5, 15, 60 min ({@link RetryPolicy}); the 5th
 * failure, or a non-retryable one → `FAILED` + `NotificationFailed`;</li>
 * <li>SMS (R-11-06): the club-local month's counter is reserved first; at `messaging.sms.monthlyCap` → `SKIPPED_CAP`, an
 * EMAIL forced to every address of the recipient without one, and `SmsCapReached{month, cap}` once per month (N-49); an
 * SMS the non-production guard refuses → `SKIPPED_NOT_ALLOWED`; `SENT` is terminal at R1 (no delivery callback);</li>
 * <li>PUSH (R-11-07): `GONE` → `FAILED`, subscription `EXPIRED` + `PushUnsubscribed`, never retried; `RETRYABLE` → retried;
 * `FAILED` → `FAILED`, and the 3rd consecutive failure of a subscription expires it.</li>
 * </ul>
 */
public class NotificationDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);
    static final Duration LEASE = Duration.ofMinutes(2);
    static final int POLL_BATCH = 200, PUSH_FAILURES_TO_EXPIRE = 3;
    private final NotificationRepository notifications; private final PushSubscriptionRepository subscriptions; private final EmailSender email;
    private final SmsSender sms; private final PushSender push; private final ClubConfigService configs; private final ClubEmailSettings emailSettings;
    private final ClubSmsUsage usage; private final NotificationEmailRenderer emails; private final UnsubscribeTokens unsubscribes;
    private final MemberDirectoryPort members; private final EventPublisher events; private final TransactionTemplate transactions;
    private final List<NotificationFactsPort> owners; private final Clock clock; private final String platformFrom;

    public NotificationDispatcher(NotificationRepository notifications, PushSubscriptionRepository subscriptions, EmailSender email, SmsSender sms,
            PushSender push, ClubConfigService configs, ClubEmailSettings emailSettings, ClubSmsUsage usage, NotificationEmailRenderer emails,
            UnsubscribeTokens unsubscribes, MemberDirectoryPort members, EventPublisher events, TransactionTemplate transactions,
            List<NotificationFactsPort> owners, Clock clock, String platformFrom) {
        this.notifications = notifications; this.subscriptions = subscriptions; this.email = email; this.sms = sms; this.push = push;
        this.configs = configs; this.emailSettings = emailSettings; this.usage = usage; this.emails = emails; this.unsubscribes = unsubscribes;
        this.members = members; this.events = events; this.transactions = transactions; this.owners = List.copyOf(owners); this.clock = clock;
        this.platformFrom = platformFrom;
    }

    /** The inline trigger: every due delivery of these notifications of the current club. Outside any transaction. */
    public int dispatch(Collection<String> notificationIds) {
        int sent = 0;
        for (String id : new java.util.LinkedHashSet<>(notificationIds)) {
            for (int guard = 0; guard < 1000; guard++) {
                String token = UUID.randomUUID().toString();
                var claimed = notifications.claimDue(id, clock.instant(), LEASE, token);
                if (claimed.isEmpty()) { break; }
                process(claimed.get(), token);
                sent++;
            }
        }
        return sent;
    }

    /** The 5-second poll (R-11-09): due deliveries of every club, retries included. */
    @Scheduled(fixedDelayString = "${notifications.dispatcher.poll-millis:5000}")
    public int poll() {
        int sent = 0;
        for (; sent < POLL_BATCH; sent++) {
            String token = UUID.randomUUID().toString();
            var claimed = notifications.claimDue(null, clock.instant(), LEASE, token);
            if (claimed.isEmpty()) { break; }
            try (var tenant = TenantContext.open(claimed.get().clubId())) { process(claimed.get(), token); }
        }
        return sent;
    }

    private void process(Notification notification, String token) {
        var delivery = notification.deliveries().stream().filter(d -> token.equals(d.claimToken())).findFirst().orElse(null);
        if (delivery == null) { return; }
        try {
            switch (delivery.channel()) {
                case EMAIL -> outcome(notification, delivery, token, sendEmail(notification, delivery));
                case SMS -> sendSms(notification, delivery, token);
                case PUSH -> sendPush(notification, delivery, token);
                case APP -> settle(notification, delivery, token, DeliveryStatus.DELIVERED, null, null);
            }
        } catch (RuntimeException failure) {
            // A sender must never throw; one that does is a transient failure of that delivery (logged without its content).
            LOG.warn("Notification delivery failed notificationId={} channel={} error={}", notification.id(), delivery.channel(), failure.getClass().getSimpleName());
            outcome(notification, delivery, token, SendResult.retryable(failure.getClass().getSimpleName()));
        }
    }

    private SendResult sendEmail(Notification notification, Notification.Delivery delivery) {
        var settings = emailSettings.get(notification.clubId(), platformFrom);
        String origin = emailSettings.appOrigin(notification.clubId()).orElse(null);
        String memberId = notification.recipient() == null ? null : notification.recipient().memberId();
        String unsubscribe = origin != null && memberId != null ? origin + "/comunicats/baixa?t=" + unsubscribes.issue(notification.clubId(), memberId) : null;
        var tags = new LinkedHashMap<String, String>(); tags.put("clubId", notification.clubId()); tags.put("notificationId", notification.id());
        var result = email.send(emails.render(notification, delivery.target(), settings, origin, unsubscribe, tags));
        if (result.sent()) { return SendResult.accepted(result.providerMessageId()); }
        return emailRetryable(result.error()) ? SendResult.retryable(result.error()) : SendResult.failed(result.error());
    }
    /**
     * R-11-09 for the `EmailSender`'s answers, which carry no flag of their own: a provider status 4xx other than 429 (a
     * rejected request, an invalid address) is final; 429, 5xx, a timeout or a transport failure is retried.
     */
    static boolean emailRetryable(String error) {
        var status = error == null ? null : java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(error);
        if (status == null || !status.find()) { return true; }
        int code = Integer.parseInt(status.group(1));
        return code == 429 || code >= 500;
    }

    private void sendSms(Notification notification, Notification.Delivery delivery, String token) {
        var config = configs.get(notification.clubId());
        var month = YearMonth.from(clock.instant().atZone(java.time.ZoneId.of(config.club().timeZone())));
        long cap = Objects.requireNonNullElse(config.get("messaging.sms.monthlyCap", Integer.class), 0);
        if (!usage.reserve(notification.clubId(), month, cap)) {
            settle(notification, delivery, token, DeliveryStatus.SKIPPED_CAP, null, "SMS monthly cap reached");
            forceEmail(notification);
            if (usage.firstCapNotice(notification.clubId(), month)) {
                transactions.executeWithoutResult(tx -> events.publish(new MessagingEvent(MessagingEvent.Kind.SmsCapReached, notification.clubId(), notification.clubId(),
                        clock.instant(), Map.of("month", month.toString(), "cap", cap), null, null, DomainEvent.Origin.SYSTEM)));
            }
            return; // the forced EMAIL deliveries are QUEUED and due: the running dispatch or poll claims them next
        }
        var tags = Map.of("clubId", notification.clubId(), "notificationId", notification.id());
        SendResult result;
        try { result = sms.send(new SmsMessage(delivery.target(), Objects.toString(notification.smsBody(), ""), config.get("messaging.sms.senderId", String.class), tags)); }
        catch (RuntimeException failure) { result = SendResult.retryable(failure.getClass().getSimpleName()); }
        if (!result.ok()) { usage.release(notification.clubId(), month); }
        if (result.refusedByGuard()) { settle(notification, delivery, token, DeliveryStatus.SKIPPED_NOT_ALLOWED, null, "Not in SMS_ALLOWED_NUMBERS"); return; }
        outcome(notification, delivery, token, result);
    }

    /** R-11-06: at the cap, an EMAIL to every address of the recipient that has none yet, whatever the preference. */
    private void forceEmail(Notification notification) {
        var recipient = notification.recipient();
        MemberContact contact = null;
        if (recipient != null && recipient.memberId() != null) { contact = members.find(recipient.memberId()).orElse(null); }
        if (contact == null && recipient != null && recipient.accountId() != null) { contact = members.byAccount(recipient.accountId()).orElse(null); }
        if (contact == null) { return; }
        var live = new ArrayList<String>();
        var current = notifications.findScoped(notification.id()).orElse(notification);
        for (var d : current.deliveries()) {
            if (d.channel() == NotificationChannel.EMAIL && d.target() != null && (d.status() == DeliveryStatus.QUEUED || d.status().reached())) { live.add(d.target()); }
        }
        var emails = contact.emails().stream().map(email -> new ChannelResolver.EmailAddress(email.address(), email.bounced())).toList();
        var forced = ChannelResolver.capReached(new ChannelResolver.Contact(contact.accountId(), emails, contact.phones(), List.of(), null), live);
        Instant now = clock.instant();
        notifications.addDeliveries(notification.id(), forced.stream()
                .map(p -> new Notification.Delivery(p.channel(), p.target(), p.status(), 0, now, null, null, null, null, null)).toList());
    }

    private void sendPush(Notification notification, Notification.Delivery delivery, String token) {
        var subscription = subscriptions.findById(delivery.target()).orElse(null);
        if (subscription == null || subscription.status() != PushSubscription.Status.ACTIVE) {
            settle(notification, delivery, token, DeliveryStatus.FAILED, null, "Push subscription expired");
            return;
        }
        String url = notification.action() == null ? "/notificacions" : "/notificacions?id=" + notification.id();
        var result = push.send(subscription, new PushPayload(notification.id(), notification.title(), notification.body(),
                notification.icon() == null ? null : notification.icon().name(), url, notification.code()));
        switch (result.status()) {
            case OK -> {
                subscriptions.pushed(subscription.id(), clock.instant());
                outcome(notification, delivery, token, SendResult.accepted(null));
            }
            case GONE -> {
                expire(subscription);
                settle(notification, delivery, token, DeliveryStatus.FAILED, null, result.error());
            }
            case RETRYABLE -> outcome(notification, delivery, token, SendResult.retryable(result.error()));
            case FAILED -> {
                if (subscriptions.failed(subscription.id()) >= PUSH_FAILURES_TO_EXPIRE) { expire(subscription); }
                outcome(notification, delivery, token, SendResult.failed(result.error()));
            }
        }
    }
    private void expire(PushSubscription subscription) {
        transactions.executeWithoutResult(tx -> {
            if (subscriptions.expire(subscription.id(), clock.instant())) {
                events.publish(new MessagingEvent(MessagingEvent.Kind.PushUnsubscribed, subscription.clubId(), subscription.id(), clock.instant(),
                        Map.of("accountId", subscription.accountId(), "endpoint", subscription.endpointHash()), null, null, DomainEvent.Origin.SYSTEM));
            }
        });
    }

    /** R-11-09: accepted, retried with backoff, or failed for good. */
    private void outcome(Notification notification, Notification.Delivery delivery, String token, SendResult result) {
        Instant now = clock.instant();
        int attempts = delivery.attempts() + 1;
        if (result.ok()) {
            if (settle(notification, delivery, token, DeliveryStatus.SENT, result.providerRef(), null)) {
                var owner = owner(notification);
                if (owner != null) {
                    try { owner.sent(view(notification), delivery.channel().name()); }
                    catch (RuntimeException failure) { LOG.warn("Owner hook after a sent notification failed code={} error={}", notification.code(), failure.getClass().getSimpleName()); }
                }
            }
            return;
        }
        var next = RetryPolicy.nextAttempt(attempts, result.retryable(), now);
        if (next.isPresent()) {
            notifications.settle(notification.id(), token, update -> update.set("deliveries.$.attempts", attempts)
                    .set("deliveries.$.nextAttemptAt", next.get()).set("deliveries.$.lastError", result.error()));
            return;
        }
        settle(notification, delivery, token, DeliveryStatus.FAILED, null, result.error());
    }

    /** Writes a final or skipped status (with its event) in one short transaction; `false` when the lease was lost. */
    private boolean settle(Notification notification, Notification.Delivery delivery, String token, DeliveryStatus status, String providerRef, String error) {
        Instant now = clock.instant();
        boolean attempted = status == DeliveryStatus.SENT || status == DeliveryStatus.FAILED;
        Boolean done = transactions.execute(tx -> {
            boolean moved = notifications.settle(notification.id(), token, update -> {
                update.set("deliveries.$.status", status.name());
                if (attempted) { update.set("deliveries.$.attempts", delivery.attempts() + 1); }
                if (providerRef != null) { update.set("deliveries.$.providerRef", providerRef); }
                if (status == DeliveryStatus.SENT) { update.set("deliveries.$.sentAt", now).set("deliveries.$.lastError", null); }
                if (status == DeliveryStatus.DELIVERED) { update.set("deliveries.$.deliveredAt", now); }
                if (status == DeliveryStatus.FAILED) { update.set("deliveries.$.failedAt", now).set("deliveries.$.lastError", error); }
                if (status.skipped()) { update.set("deliveries.$.lastError", error); }
            });
            if (moved && (status == DeliveryStatus.SENT || status == DeliveryStatus.FAILED)) {
                events.publish(new NotificationEvent(status == DeliveryStatus.SENT ? NotificationEvent.Kind.NotificationSent : NotificationEvent.Kind.NotificationFailed,
                        notification.clubId(), notification.id(), now, delivery.channel()));
            }
            return moved;
        });
        return Boolean.TRUE.equals(done);
    }

    private NotificationFactsPort owner(Notification notification) {
        if (notification.eventType() == null) { return null; }
        return owners.stream().filter(port -> port.eventTypes().contains(notification.eventType())).findFirst().orElse(null);
    }
    static NotificationFactsPort.StoredNotification view(Notification n) {
        var subject = n.subject() == null ? com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject.NONE
                : new com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject(n.subject().dogId(), n.subject().bookingId(),
                        n.subject().classSessionId(), n.subject().waitlistEntryId(), n.subject().trainingBookingId(), n.subject().invoiceId(),
                        n.subject().activityId(), n.subject().taskId(), n.subject().memberId());
        return new NotificationFactsPort.StoredNotification(n.id(), n.code(), n.eventType(), n.audience() == null ? null : n.audience().name(),
                n.recipient() == null ? null : n.recipient().accountId(), n.recipient() == null ? null : n.recipient().memberId(), subject,
                n.deliveries() == null ? List.of() : n.deliveries().stream().map(d -> new NotificationFactsPort.DeliveryView(d.channel().name(), d.status().name())).toList(), false);
    }
}
