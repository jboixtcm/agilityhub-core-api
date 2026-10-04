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
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S11 §5/§6 `dispatch` and R-11-09: sends the `QUEUED` deliveries whose `nextAttemptAt ≤ now`, triggered inline right after
 * the engine's commit and by a poll every 5 s. Each delivery is claimed with one atomic `findAndModify` that leases it
 * (`claimToken`, `claimedUntil`), so two instances never send it twice (T-11-30). An attempt has two separate parts: the
 * provider call, outside any Mongo transaction (decision E13), and the settlement of what it produced, one short
 * transaction with its events (AGENTS rule 8). A settlement that fails is written again with the same outcome, never by a
 * new provider call: an accepted message is never sent twice (E7-T02 round 2, E7-T05).
 *
 * <ul>
 * <li>before each attempt, retries included, every owner of the notification's event must still find it deliverable
 * ({@link NotificationFactsPort#deliverable}); otherwise → `SKIPPED_STALE` and nothing is sent (S10 R-10-10: an N-20
 * retried after the dog changed hands never reaches its previous owner);</li>
 * <li>EMAIL (R-11-08, E7-T05): before each attempt, retries included, the address is checked against both bounce marks
 * ({@link EmailSuppression}); a suppressed one → `SKIPPED_NO_CONTACT`, as a delivery resolved without an address: no
 * provider call, no event;</li>
 * <li>accepted → `SENT` (`providerRef`, `sentAt`, `attempts++`) + `NotificationSent`; when the SendGrid webhook already moved
 * the delivery to `DELIVERED` or `FAILED` while the call was in flight, that final state stays and the settlement only
 * records `providerRef`, `sentAt` and the attempt (R-11-08, as E1's `finish()`);</li>
 * <li>retryable → back to `QUEUED`, `attempts++`, `nextAttemptAt` = now + 1, 5, 15, 60 min ({@link RetryPolicy}); the 5th
 * failure, or a non-retryable one → `FAILED` + `NotificationFailed`;</li>
 * <li>SMS (R-11-06): the club-local month's counter is reserved first (in a later month when another sender already began
 * it, {@link ClubSmsUsage.Reservation}); at `messaging.sms.monthlyCap` one transaction writes `SKIPPED_CAP`, an EMAIL forced
 * to every address of the recipient that neither bounce mark suppresses ({@link EmailSuppression}) and that has no live
 * EMAIL delivery (a conditional insert: one per address, whoever settles which phone), and — the first time in the month
 * — the marker with `SmsCapReached{month, cap}` (N-49); an SMS the non-production guard refuses → `SKIPPED_NOT_ALLOWED`;
 * `SENT` is terminal at R1 (no delivery callback);</li>
 * <li>PUSH (R-11-07): a subscription that is no longer `ACTIVE`, or that is not the recipient account's, → `FAILED` without a
 * call (E76); `GONE` → `FAILED`, subscription `EXPIRED` + `PushUnsubscribed`, never retried; `RETRYABLE` → retried;
 * `FAILED` → `FAILED`, and the 3rd consecutive failure of a subscription expires it. The subscription's bookkeeping of
 * the answer (`lastSuccessAt`, the failure count, the expiry and its event) is part of the settlement's transaction
 * (E7-T05).</li>
 * </ul>
 *
 * <p>An accepted attempt is recorded before its settlement (E7-T05): one small update writes `acceptedAt` and the
 * `providerRef` on the claimed delivery and renews its lease. {@link NotificationRepository#claimDue} never claims such a
 * delivery again; once its lease expires, any dispatcher (this one, another instance, or this one after a restart) claims
 * it with {@link NotificationRepository#claimAccepted} and settles it with the stored acceptance, without a provider
 * call. A settlement is retried {@value #SETTLE_ATTEMPTS} times (a write conflict with another dispatcher or the webhook,
 * a failed outbox write). One that still fails and carries a provider's answer (an acceptance, a push service's answer)
 * waits in this instance and is written again before the next claims; any other outcome is left to its lease: nothing
 * was sent, and the poll attempts it again when the lease expires. A provider call is made only while the lease has at
 * least {@link #PROVIDER_WINDOW} left, so a sender whose preparation outlasted its lease never sends what another one
 * has claimed since.</p>
 */
public class NotificationDispatcher {
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.shared.application.SignupCapabilities paymentCapabilities;
    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);
    static final Duration LEASE = Duration.ofMinutes(2);
    /** The lease a provider call needs: twice the providers' own bound (5 s to connect + 10 s for the answer). */
    static final Duration PROVIDER_WINDOW = Duration.ofSeconds(30);
    static final int POLL_BATCH = 200, PUSH_FAILURES_TO_EXPIRE = 3, SETTLE_ATTEMPTS = 5;
    static final String STALE = "No longer relevant to its recipient", CAP_REACHED = "SMS monthly cap reached", NOT_ALLOWED = "Not in SMS_ALLOWED_NUMBERS",
            EXPIRED = "Push subscription expired", OTHER_OWNER = "Push subscription of another account", SUPPRESSED = "Email address suppressed";
    private final NotificationRepository notifications; private final PushSubscriptionRepository subscriptions; private final EmailSender email;
    private final SmsSender sms; private final PushSender push; private final ClubConfigService configs; private final ClubEmailSettings emailSettings;
    private final ClubSmsUsage usage; private final NotificationEmailRenderer emails; private final UnsubscribeTokens unsubscribes;
    private final MemberDirectoryPort members; private final NotificationAccounts accounts; private final EventPublisher events; private final TransactionTemplate transactions;
    private final List<NotificationFactsPort> owners; private final Clock clock; private final String platformFrom;
    /** Settlements with a provider's answer that kept failing: written again before the next claims, while their token still holds the lease. */
    private final Queue<Settling> unsettled = new ConcurrentLinkedQueue<>();

    public NotificationDispatcher(NotificationRepository notifications, PushSubscriptionRepository subscriptions, EmailSender email, SmsSender sms,
            PushSender push, ClubConfigService configs, ClubEmailSettings emailSettings, ClubSmsUsage usage, NotificationEmailRenderer emails,
            UnsubscribeTokens unsubscribes, MemberDirectoryPort members, NotificationAccounts accounts, EventPublisher events, TransactionTemplate transactions,
            List<NotificationFactsPort> owners, Clock clock, String platformFrom) {
        this.notifications = notifications; this.subscriptions = subscriptions; this.email = email; this.sms = sms; this.push = push;
        this.configs = configs; this.emailSettings = emailSettings; this.usage = usage; this.emails = emails; this.unsubscribes = unsubscribes;
        this.members = members; this.accounts = accounts; this.events = events; this.transactions = transactions; this.owners = List.copyOf(owners);
        this.clock = clock; this.platformFrom = platformFrom;
    }

    /**
     * The inline trigger: every due delivery of these notifications of the current club, and any accepted one of them whose
     * settlement is still pending past its lease. Outside any transaction.
     */
    public int dispatch(Collection<String> notificationIds) {
        settleWaiting();
        int sent = 0;
        for (String id : new java.util.LinkedHashSet<>(notificationIds)) {
            sent += claimAll((now, token) -> notifications.claimAccepted(id, now, LEASE, token), false, Integer.MAX_VALUE);
            sent += claimAll((now, token) -> notifications.claimDue(id, now, LEASE, token), false, Integer.MAX_VALUE);
        }
        return sent;
    }

    /** The 5-second poll (R-11-09): the accepted deliveries left unsettled past their lease, then the due ones of every club, retries included. */
    @Scheduled(fixedDelayString = "${notifications.dispatcher.poll-millis:5000}")
    public int poll() {
        settleWaiting();
        int sent = claimAll((now, token) -> notifications.claimAccepted(null, now, LEASE, token), true, POLL_BATCH);
        return sent + claimAll((now, token) -> notifications.claimDue(null, now, LEASE, token), true, POLL_BATCH - sent);
    }
    private interface Claim { java.util.Optional<Notification> next(Instant now, String token); }
    private int claimAll(Claim claim, boolean openTenant, int limit) {
        int processed = 0;
        for (int guard = 0; processed < limit && guard < 1000; guard++) {
            String token = UUID.randomUUID().toString();
            var claimed = claim.next(clock.instant(), token);
            if (claimed.isEmpty()) { break; }
            if (openTenant) { try (var tenant = TenantContext.open(claimed.get().clubId())) { process(claimed.get(), token); } }
            else { process(claimed.get(), token); }
            processed++;
        }
        return processed;
    }

    /**
     * One claimed delivery: an attempt (the provider call, its acceptance recorded at once, then the settlement — never the
     * call again), or the settlement of an acceptance recorded by an earlier claim.
     */
    private void process(Notification notification, String token) {
        var delivery = notification.deliveries().stream().filter(d -> token.equals(d.claimToken())).findFirst().orElse(null);
        if (delivery == null) { return; }
        if (delivery.acceptedAt() != null) { settle(new Settling(notification, delivery, token, Outcome.recorded(delivery))); return; }
        Outcome outcome;
        try { outcome = attempt(notification, delivery); }
        catch (RuntimeException failure) {
            // A sender or an owner's hook must never throw; one that does is a transient failure of that attempt (logged without its content).
            LOG.warn("Notification delivery failed notificationId={} channel={} error={}", notification.id(), delivery.channel(), failure.getClass().getSimpleName());
            outcome = Outcome.of(SendResult.retryable(failure.getClass().getSimpleName()));
        }
        if (outcome.abandoned()) {
            LOG.warn("Notification attempt abandoned: its lease ends too soon notificationId={} channel={}", notification.id(), delivery.channel());
            return;
        }
        if (outcome.accepted()) {
            outcome = outcome.at(clock.instant());
            record(new Settling(notification, delivery, token, outcome));
        }
        settle(new Settling(notification, delivery, token, outcome));
    }

    private Outcome attempt(Notification notification, Notification.Delivery delivery) {
        if (!deliverable(notification, delivery)) { return Outcome.status(DeliveryStatus.SKIPPED_STALE, STALE); }
        if (delivery.channel() == NotificationChannel.EMAIL && suppressed(notification, delivery.target())) {
            return Outcome.status(DeliveryStatus.SKIPPED_NO_CONTACT, SUPPRESSED);
        }
        return switch (delivery.channel()) {
            case EMAIL -> sendEmail(notification, delivery);
            case SMS -> sendSms(notification, delivery);
            case PUSH -> sendPush(notification, delivery);
            case APP -> Outcome.status(DeliveryStatus.DELIVERED, null);
        };
    }
    /** The claim still covers a provider call: at least {@link #PROVIDER_WINDOW} of the lease is left. */
    private boolean leaseCoversCall(Notification.Delivery delivery) {
        return delivery.claimedUntil() != null && clock.instant().plus(PROVIDER_WINDOW).isBefore(delivery.claimedUntil());
    }

    private Outcome sendEmail(Notification notification, Notification.Delivery delivery) {
        var settings = emailSettings.get(notification.clubId(), platformFrom);
        String origin = emailSettings.appOrigin(notification.clubId()).orElse(null);
        String memberId = notification.recipient() == null ? null : notification.recipient().memberId();
        String unsubscribe = origin != null && memberId != null ? origin + "/comunicats/baixa?t=" + unsubscribes.issue(notification.clubId(), memberId) : null;
        var tags = new LinkedHashMap<String, String>(); tags.put("clubId", notification.clubId()); tags.put("notificationId", notification.id());
        var message = emails.render(notification, delivery.target(), settings, app -> emailSettings.appOrigin(notification.clubId(), app).orElse(null), unsubscribe, tags);
        // N-01 retains only a marker. Issue the 24-hour capability in memory at delivery, never in the notification document.
        String signupMemberId = notification.subject() == null ? null : notification.subject().memberId();
        if ("N-01".equals(notification.code()) && notification.audience() == com.agilityhub.core.clubs.messaging.domain.NotificationAudience.APPLICANT
                && signupMemberId != null && origin != null && message.text().contains(com.agilityhub.core.shared.application.SignupCapabilities.PAYMENT_RETRY_MARKER)) {
            String link = origin + "/alta/pagament#memberId=" + signupMemberId + "&signupToken=" + paymentCapabilities.issue(signupMemberId);
            String marker = com.agilityhub.core.shared.application.SignupCapabilities.PAYMENT_RETRY_MARKER;
            message = new com.agilityhub.core.clubs.messaging.application.EmailMessage(message.to(), message.subject(),
                    message.html().replace(marker, link.replace("&", "&amp;")), message.text().replace(marker, link), message.from(), message.replyTo(),
                    message.locale(), message.tags(), message.headers());
        }
        if (!leaseCoversCall(delivery)) { return Outcome.ABANDONED; }
        var result = email.send(message);
        if (result.sent()) { return Outcome.of(SendResult.accepted(result.providerMessageId())); }
        return Outcome.of(emailRetryable(result.error()) ? SendResult.retryable(result.error()) : SendResult.failed(result.error()));
    }
    /**
     * R-11-08 at every attempt: the address is suppressed when either mark says so ({@link EmailSuppression#suppressed}) — the
     * recipient's member contact (by member, else by account; without one, the club's members that have the address, those
     * the webhook marks) and the recipient account's `emailStatus` on its login address.
     */
    private boolean suppressed(Notification notification, String address) {
        var recipient = notification.recipient();
        var contact = contact(recipient);
        var holders = contact != null ? List.of(contact) : members.findAll(members.membersWithEmail(address));
        String accountId = contact != null && contact.accountId() != null ? contact.accountId() : recipient == null ? null : recipient.accountId();
        var account = accountId == null ? null : accounts.find(accountId).orElse(null);
        return EmailSuppression.suppressed(address, holders, account);
    }
    /** The recipient's member contact: by member, else by account (`null` for an applicant or an admin without a member record). */
    private MemberContact contact(Notification.Recipient recipient) {
        if (recipient == null) { return null; }
        MemberContact contact = null;
        if (recipient.memberId() != null) { contact = members.find(recipient.memberId()).orElse(null); }
        if (contact == null && recipient.accountId() != null) { contact = members.byAccount(recipient.accountId()).orElse(null); }
        return contact;
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

    private Outcome sendSms(Notification notification, Notification.Delivery delivery) {
        var config = configs.get(notification.clubId());
        var month = YearMonth.from(clock.instant().atZone(java.time.ZoneId.of(config.club().timeZone())));
        long cap = Objects.requireNonNullElse(config.get("messaging.sms.monthlyCap", Integer.class), 0);
        var reservation = usage.reserve(notification.clubId(), month, cap);
        if (!reservation.granted()) { return Outcome.cap(reservation.month(), cap); }
        Runnable release = () -> quietly("SMS reservation release", () -> usage.release(notification.clubId(), reservation.month()));
        if (!leaseCoversCall(delivery)) { release.run(); return Outcome.ABANDONED; }
        var tags = Map.of("clubId", notification.clubId(), "notificationId", notification.id());
        SendResult result;
        try { result = sms.send(new SmsMessage(delivery.target(), Objects.toString(notification.smsBody(), ""), config.get("messaging.sms.senderId", String.class), tags)); }
        catch (RuntimeException failure) { result = SendResult.retryable(failure.getClass().getSimpleName()); }
        if (!result.ok()) { release.run(); }
        if (result.refusedByGuard()) { return Outcome.status(DeliveryStatus.SKIPPED_NOT_ALLOWED, NOT_ALLOWED); }
        return Outcome.of(result);
    }

    private Outcome sendPush(Notification notification, Notification.Delivery delivery) {
        var subscription = subscriptions.findById(delivery.target()).orElse(null);
        if (subscription == null || subscription.status() != PushSubscription.Status.ACTIVE) { return Outcome.status(DeliveryStatus.FAILED, EXPIRED); }
        // R-11-07 (E76): the device must still be the recipient's at every attempt, never the next account's on the same browser.
        String recipient = notification.recipient() == null ? null : notification.recipient().accountId();
        if (!subscription.accountId().equals(recipient)) { return Outcome.status(DeliveryStatus.FAILED, OTHER_OWNER); }
        String url = notification.action() == null ? "/notificacions" : "/notificacions?id=" + notification.id();
        var payload = new PushPayload(notification.id(), notification.title(), notification.body(), notification.icon() == null ? null : notification.icon().name(),
                url, notification.code());
        if (!leaseCoversCall(delivery)) { return Outcome.ABANDONED; }
        var result = push.send(subscription, payload);
        // The subscription's bookkeeping of the answer is the settlement's (E7-T05): it never turns the answer into another attempt.
        var answer = new PushAnswer(subscription.id(), result.status());
        return switch (result.status()) {
            case OK -> Outcome.of(SendResult.accepted(null)).with(answer);
            case GONE -> Outcome.status(DeliveryStatus.FAILED, result.error()).with(answer);
            case RETRYABLE -> Outcome.of(SendResult.retryable(result.error()));
            case FAILED -> Outcome.of(SendResult.failed(result.error())).with(answer);
        };
    }
    /**
     * R-11-07 in the settlement's transaction: `lastSuccessAt` for an accepted push; `GONE` → the subscription `EXPIRED` +
     * `PushUnsubscribed`; a `FAILED` counts one consecutive failure, and the 3rd expires it the same way.
     */
    private void bookkeeping(PushAnswer answer, Instant at) {
        switch (answer.status()) {
            case OK -> subscriptions.pushed(answer.subscriptionId(), at);
            case GONE -> expire(answer.subscriptionId(), at);
            case FAILED -> { if (subscriptions.failed(answer.subscriptionId()) >= PUSH_FAILURES_TO_EXPIRE) { expire(answer.subscriptionId(), at); } }
            case RETRYABLE -> { }
        }
    }
    private void expire(String subscriptionId, Instant at) {
        var subscription = subscriptions.findById(subscriptionId).orElse(null);
        if (subscription != null && subscriptions.expire(subscriptionId, at)) {
            events.publish(new MessagingEvent(MessagingEvent.Kind.PushUnsubscribed, subscription.clubId(), subscription.id(), at,
                    Map.of("accountId", subscription.accountId(), "endpoint", subscription.endpointHash()), null, null, DomainEvent.Origin.SYSTEM));
        }
    }
    private static void quietly(String what, Runnable step) {
        try { step.run(); }
        catch (RuntimeException failure) { LOG.warn("Notification dispatcher: {} failed error={}", what, failure.getClass().getSimpleName()); }
    }

    // ---- Settlement: one transaction per outcome, written again (never re-sent) when it fails.

    /**
     * What one attempt produced: a provider's answer (`result`), a status reached without one (`status`, `error`), or the SMS
     * cap of `capMonth` (`cap`); `acceptedAt`, the moment an accepted answer arrived; `push`, the subscription's bookkeeping of
     * a push service's answer. {@link #ABANDONED}: no provider call and nothing to write.
     */
    record Outcome(SendResult result, DeliveryStatus status, String error, YearMonth capMonth, long cap, Instant acceptedAt, PushAnswer push) {
        static final Outcome ABANDONED = new Outcome(null, null, null, null, 0, null, null);
        static Outcome of(SendResult result) { return new Outcome(result, null, null, null, 0, null, null); }
        static Outcome status(DeliveryStatus status, String error) { return new Outcome(null, status, error, null, 0, null, null); }
        static Outcome cap(YearMonth month, long cap) { return new Outcome(null, DeliveryStatus.SKIPPED_CAP, CAP_REACHED, month, cap, null, null); }
        /** The acceptance an earlier claim recorded on the delivery (`acceptedAt`, `providerRef`): settled, never sent again. */
        static Outcome recorded(Notification.Delivery delivery) {
            var push = delivery.channel() == NotificationChannel.PUSH ? new PushAnswer(delivery.target(), PushResult.Status.OK) : null;
            return new Outcome(SendResult.accepted(delivery.providerRef()), null, null, null, 0, delivery.acceptedAt(), push);
        }
        Outcome with(PushAnswer answer) { return new Outcome(result, status, error, capMonth, cap, acceptedAt, answer); }
        Outcome at(Instant at) { return new Outcome(result, status, error, capMonth, cap, at, push); }
        boolean accepted() { return result != null && result.ok(); }
        boolean abandoned() { return result == null && status == null; }
        /** An answer the delivery's state depends on: written again, never asked again, while this instance holds the lease. */
        boolean keepsAnswer() { return accepted() || push != null; }
    }
    /** A push service's answer for one subscription (R-11-07). */
    record PushAnswer(String subscriptionId, PushResult.Status status) { }
    /** An outcome to write: the claimed delivery (as claimed) and its lease token. */
    private record Settling(Notification notification, Notification.Delivery delivery, String token, Outcome outcome) { }

    /**
     * E7-T05 (R-11-09): the provider accepted — `acceptedAt` and the reference go on the claimed delivery at once, in their own
     * small update (retried like a settlement), so that no claim sends it again whatever happens to the settlement. When even
     * this write fails, the acceptance lives in this instance only (`unsettled`), under the lease of its claim.
     */
    private void record(Settling s) {
        for (int attempt = 1; ; attempt++) {
            try {
                if (!notifications.markAccepted(s.notification().id(), s.token(), s.outcome().result().providerRef(), s.outcome().acceptedAt(), LEASE)) {
                    LOG.warn("Notification acceptance found its lease lost notificationId={} channel={}", s.notification().id(), s.delivery().channel());
                }
                return;
            } catch (RuntimeException failure) {
                if (attempt >= SETTLE_ATTEMPTS) {
                    LOG.warn("Notification acceptance could not be recorded notificationId={} channel={} error={}", s.notification().id(), s.delivery().channel(),
                            failure.getClass().getSimpleName());
                    return;
                }
                pause();
            }
        }
    }

    private void settle(Settling settling) {
        for (int attempt = 1; ; attempt++) {
            try { write(settling); return; }
            catch (RuntimeException failure) {
                if (attempt >= SETTLE_ATTEMPTS) {
                    LOG.warn("Notification settlement failed notificationId={} channel={} accepted={} error={}", settling.notification().id(),
                            settling.delivery().channel(), settling.outcome().accepted(), failure.getClass().getSimpleName());
                    if (settling.outcome().keepsAnswer()) { unsettled.add(settling); }
                    return;
                }
                pause();
            }
        }
    }
    /** The settlements with a provider's answer a previous try could not write, once each; those that fail again keep waiting. */
    private void settleWaiting() {
        for (int waiting = unsettled.size(); waiting > 0; waiting--) {
            var settling = unsettled.poll();
            if (settling == null) { return; }
            try (var tenant = TenantContext.open(settling.notification().clubId())) { write(settling); }
            catch (RuntimeException failure) { unsettled.add(settling); }
        }
    }
    private static void pause() {
        try { Thread.sleep(TransactionRetries.jitter()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    /**
     * The settlement's transaction: the delivery, its event and — while the claim is still this token's — the push
     * subscription's bookkeeping of the answer; after an accepted send's commit, the owner's `sent` hook (outside any
     * transaction).
     */
    private void write(Settling s) {
        var outcome = s.outcome();
        var settlement = transactions.execute(tx -> {
            NotificationRepository.Settlement settled;
            if (outcome.capMonth() != null) { settled = capReached(s); }
            else if (outcome.result() == null) { settled = status(s, outcome.status(), outcome.error()); }
            else if (outcome.accepted()) { settled = accepted(s, outcome.result().providerRef()); }
            else { settled = failed(s, outcome.result()); }
            if (outcome.push() != null && settled != NotificationRepository.Settlement.LOST) {
                bookkeeping(outcome.push(), outcome.acceptedAt() != null ? outcome.acceptedAt() : clock.instant());
            }
            return settled;
        });
        if (outcome.accepted() && settlement != null && settlement != NotificationRepository.Settlement.LOST) {
            var owner = owner(s.notification());
            if (owner != null) {
                try { owner.sent(view(s.notification()), s.delivery().channel().name()); }
                catch (RuntimeException failure) { LOG.warn("Owner hook after a sent notification failed code={} error={}", s.notification().code(), failure.getClass().getSimpleName()); }
            }
        }
    }

    /**
     * Accepted: `SENT` + `NotificationSent`, or the webhook's final state kept with the acceptance's data. `sentAt` is the
     * moment the provider accepted, also when the settlement is written later.
     */
    private NotificationRepository.Settlement accepted(Settling s, String providerRef) {
        Instant sentAt = s.outcome().acceptedAt() != null ? s.outcome().acceptedAt() : clock.instant(); int attempts = s.delivery().attempts() + 1;
        var settlement = notifications.settleClaim(s.notification().id(), s.token(), update -> {
            update.set("deliveries.$.status", DeliveryStatus.SENT.name()).set("deliveries.$.attempts", attempts).set("deliveries.$.sentAt", sentAt).set("deliveries.$.lastError", null);
            if (providerRef != null) { update.set("deliveries.$.providerRef", providerRef); }
        }, update -> {
            update.set("deliveries.$.attempts", attempts).set("deliveries.$.sentAt", sentAt);
            if (providerRef != null) { update.set("deliveries.$.providerRef", providerRef); }
        });
        if (settlement == NotificationRepository.Settlement.MOVED) { publish(s, NotificationEvent.Kind.NotificationSent, sentAt); }
        return settlement;
    }

    /** R-11-09: retried with backoff, or failed for good. A delivery the webhook moved meanwhile keeps its state. */
    private NotificationRepository.Settlement failed(Settling s, SendResult result) {
        Instant now = clock.instant(); int attempts = s.delivery().attempts() + 1;
        var next = RetryPolicy.nextAttempt(attempts, result.retryable(), now);
        if (next.isEmpty()) { return status(s, DeliveryStatus.FAILED, result.error()); }
        return notifications.settleClaim(s.notification().id(), s.token(), update -> update.set("deliveries.$.attempts", attempts)
                .set("deliveries.$.nextAttemptAt", next.get()).set("deliveries.$.lastError", result.error()), update -> { });
    }

    /** A final or skipped status (with its event when the delivery was attempted). */
    private NotificationRepository.Settlement status(Settling s, DeliveryStatus status, String error) {
        Instant now = clock.instant(); int attempts = s.delivery().attempts() + 1;
        boolean attempted = status == DeliveryStatus.SENT || status == DeliveryStatus.FAILED;
        var settlement = notifications.settleClaim(s.notification().id(), s.token(), update -> {
            update.set("deliveries.$.status", status.name());
            if (attempted) { update.set("deliveries.$.attempts", attempts); }
            if (status == DeliveryStatus.DELIVERED) { update.set("deliveries.$.deliveredAt", now); }
            if (status == DeliveryStatus.FAILED) { update.set("deliveries.$.failedAt", now).set("deliveries.$.lastError", error); }
            if (status.skipped()) { update.set("deliveries.$.lastError", error); }
        }, update -> { if (attempted) { update.set("deliveries.$.attempts", attempts); } });
        if (settlement == NotificationRepository.Settlement.MOVED && status == DeliveryStatus.FAILED) { publish(s, NotificationEvent.Kind.NotificationFailed, now); }
        return settlement;
    }

    /**
     * R-11-06 at the cap, in the settlement's one transaction: `SKIPPED_CAP`, the forced e-mails (one conditional insert per
     * address that neither bounce mark suppresses), `NotificationQueued{EMAIL}` when one was added, and the month's first-notice
     * marker with `SmsCapReached{month, cap}` (N-49 once per month). The recipient is read first, before any write.
     */
    private NotificationRepository.Settlement capReached(Settling s) {
        var addresses = forcedAddresses(s.notification());
        var settlement = notifications.settleClaim(s.notification().id(), s.token(),
                update -> update.set("deliveries.$.status", DeliveryStatus.SKIPPED_CAP.name()).set("deliveries.$.lastError", CAP_REACHED), update -> { });
        if (settlement != NotificationRepository.Settlement.MOVED) { return settlement; }
        Instant now = clock.instant(); boolean queued = false;
        for (String address : addresses) {
            queued |= notifications.addEmailIfAbsent(s.notification().id(), new Notification.Delivery(NotificationChannel.EMAIL, address, DeliveryStatus.QUEUED, 0, now,
                    null, null, null, null, null));
        }
        if (queued) {
            events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued, s.notification().clubId(), s.notification().id(), now, NotificationChannel.EMAIL));
        }
        if (usage.firstCapNotice(s.notification().clubId(), s.outcome().capMonth())) {
            events.publish(new MessagingEvent(MessagingEvent.Kind.SmsCapReached, s.notification().clubId(), s.notification().clubId(), now,
                    Map.of("month", s.outcome().capMonth().toString(), "cap", s.outcome().cap()), null, null, DomainEvent.Origin.SYSTEM));
        }
        return settlement;
    }
    /** The recipient's addresses for the forced e-mail: every one neither bounce mark suppresses ({@link EmailSuppression}). */
    private List<String> forcedAddresses(Notification notification) {
        var recipient = notification.recipient();
        var contact = contact(recipient);
        if (contact == null) { return List.of(); }
        String accountId = contact.accountId() != null ? contact.accountId() : recipient.accountId();
        var account = accountId == null ? null : accounts.find(accountId).orElse(null);
        var forced = ChannelResolver.capReached(new ChannelResolver.Contact(contact.accountId(), EmailSuppression.addresses(contact, account), contact.phones(), List.of(), null),
                List.of());
        return forced.stream().map(ChannelResolver.Planned::target).toList();
    }

    private void publish(Settling s, NotificationEvent.Kind kind, Instant now) {
        events.publish(new NotificationEvent(kind, s.notification().clubId(), s.notification().id(), now, s.delivery().channel()));
    }

    /** Every owner of the notification's event agrees that it still applies to its recipient (an owner-less event: always). */
    private boolean deliverable(Notification notification, Notification.Delivery delivery) {
        if (notification.eventType() == null) { return true; }
        var view = view(notification);
        return owners.stream().filter(port -> port.eventTypes().contains(notification.eventType())).allMatch(port -> port.deliverable(view, delivery.channel().name()));
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
