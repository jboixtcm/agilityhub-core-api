package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.domain.NotificationEvent;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SYSTEM e-mail path (S11 R-11-01, T-11-25): product copy from `messages_*`, no template, preferences ignored, always
 * e-mail, in the account's language — N-25, N-26, N-27, N-39, N-43, N-52, N-53, and N-02's welcome e-mail, whose S01
 * magic link is a credential that never goes through the engine (E7-T02). Each row is one `Notification` with a single
 * EMAIL delivery and the E1 compatibility block, `dedupKey` = its id. Every other notice is the S11 engine's
 * (`NotificationEngine`); E7-T02 removed the E3–E6 row helpers.
 */
public class SystemNotificationService {
    /** AUTH-03 (S01 R-01-04): the access and recovery e-mails, sent once more after a provider timeout or 5xx. */
    static final Set<String> ACCESS_CODES = Set.of("N-25", "N-27");
    private static final Pattern RETRYABLE = Pattern.compile("\\btimeout\\b|\\bHTTP 5\\d\\d\\b");
    private final NotificationAccounts accounts;
    private final ClubEmailSettings clubs;
    private final ParameterCatalog parameters;
    private final NotificationRepository notifications;
    private final EmailSender sender;
    private final SystemEmailRenderer renderer;
    private final EventPublisher events;
    private final TransactionTemplate transactions;
    private final IcuMessageSource messages;
    private final Clock clock;
    private final String platformFrom;
    public SystemNotificationService(NotificationAccounts accounts, ClubEmailSettings clubs, ParameterCatalog parameters,
            NotificationRepository notifications, EmailSender sender, SystemEmailRenderer renderer, EventPublisher events,
            TransactionTemplate transactions, IcuMessageSource messages, Clock clock, String platformFrom) {
        this.accounts = accounts; this.clubs = clubs; this.parameters = parameters; this.notifications = notifications;
        this.sender = sender; this.renderer = renderer; this.events = events; this.transactions = transactions;
        this.messages = messages; this.clock = clock; this.platformFrom = platformFrom;
    }
    /** Invoke after the identity transaction commits; network delivery never runs inside a Mongo transaction. */
    @Transactional(propagation = Propagation.NEVER)
    public String send(String code, String accountId, Map<String, ?> variables) {
        return deliver(UUID.randomUUID().toString(), code, accountId, variables);
    }
    /** Called only after issuing an account-bound RESET token, never for LOGIN/welcome/resend links. */
    @Transactional(propagation = Propagation.NEVER)
    public String sendPasswordRecovery(String accountId, String link) {
        return deliverTo(UUID.randomUUID().toString(), "N-25",
                accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)), Map.of("link", link),
                sender.forPasswordRecovery());
    }
    @Transactional(propagation = Propagation.NEVER)
    public String sendAdminWelcomeOnce(String id, String accountId, String link, int expiryMinutes) {
        if (completed(id)) { return id; }
        var account = accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return deliverTo(id, "N-27", account, Map.of("link", link, "variant", "ADMIN_WELCOME", "expires_minutes", expiryMinutes),
                sender.forAdminWelcome(TenantContext.require()));
    }

    /**
     * AUTH-02 (S03 R-03-06b): N-25's `EMAIL_CHANGE` variant, the confirmation link of a login e-mail change, to the NEW address
     * only (nothing goes to the old one). The account's suppression mark belongs to its current address, so it does not stop
     * this message; the provider's own suppression of the new address still applies.
     */
    @Transactional(propagation = Propagation.NEVER)
    public String sendEmailChange(String accountId, String newEmail, String link, int expiryMinutes) {
        var account = accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return deliverTo(UUID.randomUUID().toString(), "N-25", new NotificationAccounts.Recipient(account.id(), newEmail, account.locale(), null),
                Map.of("link", link, "variant", "EMAIL_CHANGE", "expires_minutes", expiryMinutes));
    }

    public boolean completed(String id) {
        return notifications.findScoped(id).filter(item -> item.status() != Notification.Status.QUEUED).isPresent();
    }
    /** Whether the row reached the provider: SENT once the provider accepted it, or DELIVERED. */
    public boolean sent(String id) {
        return notifications.findScoped(id).filter(item -> item.status() == Notification.Status.SENT || item.status() == Notification.Status.DELIVERED).isPresent();
    }
    @Transactional(propagation = Propagation.NEVER)
    public String sendOnce(String id, String code, String accountId, Map<String, ?> variables) {
        if (completed(id)) { return id; }
        return deliver(id, code, accountId, variables);
    }
    @Transactional(propagation = Propagation.NEVER)
    public String sendOnceLocalized(String id, String code, String accountId, String locale, Map<String, ?> variables) {
        if (completed(id)) { return id; }
        var account = accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return deliverTo(id, code, new NotificationAccounts.Recipient(account.id(), account.email(), locale == null ? account.locale() : locale, account.emailStatus()), variables);
    }
    private String deliver(String id, String code, String accountId, Map<String, ?> variables) {
        return deliverTo(id, code, accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)), variables);
    }
    private String deliverTo(String id, String code, NotificationAccounts.Recipient account, Map<String, ?> variables) {
        return deliverTo(id, code, account, variables, sender);
    }
    private String deliverTo(String id, String code, NotificationAccounts.Recipient account, Map<String, ?> variables, EmailSender deliverySender) {
        String clubId = TenantContext.current();
        if (deliverySender.deliveryDisabled()) {
            var skipped = new Notification(id, clubId, account.id(), code, "EMAIL", Notification.Status.SKIPPED_NOT_ALLOWED,
                    null, null, DisabledEmailSender.DISABLED, account.email(), account.locale(), clock.instant(), null);
            transactions.executeWithoutResult(tx -> {
                if (notifications.findScoped(id).isEmpty()) { notifications.queue(skipped); }
                else { notifications.finish(id, Notification.Status.SKIPPED_NOT_ALLOWED, null, DisabledEmailSender.DISABLED, clock.instant()); }
            });
            return id;
        }
        var settings = clubId == null ? new ClubEmailSettings.Settings("AgilityHub", null, "#2563eb", "#ffffff",
                platformFrom, "AgilityHub", null, "ca", parameters.defaultInteger("auth.magicLinkMinutes"))
                : clubs.get(clubId, platformFrom);
        Locale locale = Locale.forLanguageTag(account.locale() == null ? settings.defaultLocale() : account.locale());
        if (!messages.supports(locale)) { locale = Locale.forLanguageTag(settings.defaultLocale()); }
        var tags = new java.util.HashMap<String, String>();
        tags.put("notificationId", id);
        if (clubId != null) { tags.put("clubId", clubId); }
        var email = renderer.render(code, account.email(), locale, variables, settings, tags);
        var notification = new Notification(id, clubId, account.id(), code, "EMAIL", Notification.Status.QUEUED,
                null, null, null, account.email(), locale.toLanguageTag(), clock.instant(), null);
        transactions.executeWithoutResult(tx -> {
            if (notifications.findScoped(id).isEmpty()) {
                notifications.queue(notification);
                events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued, clubId, id, clock.instant()));
            }
        });
        var first = account.emailStatus() == null ? deliverySender.send(email) : EmailSender.SendResult.failed("Recipient email suppressed");
        boolean access = access(code, variables);
        // AUTH-03 (S01 R-01-04): one more attempt, at once, when the provider timed out or answered 5xx. A timeout is ambiguous:
        // the provider may have accepted the first one, and the member then gets the same link twice.
        boolean retried = access && retryable(first);
        if (retried) {
            org.slf4j.LoggerFactory.getLogger(SystemNotificationService.class).warn("Access e-mail {} sent once more after a provider failure ({})", code, first.error());
        }
        var result = retried ? deliverySender.send(email) : first;
        transactions.executeWithoutResult(tx -> {
            if (notifications.finish(id, result.sent() ? Notification.Status.SENT : Notification.Status.FAILED,
                    result.providerMessageId(), result.error(), clock.instant(), retried ? 2 : 1)) {
                events.publish(new NotificationEvent(result.sent() ? NotificationEvent.Kind.NotificationSent : NotificationEvent.Kind.NotificationFailed,
                        clubId, id, clock.instant()));
            }
            // AUTH-03 (S03 R-03-06b): the mark on the member's record, from the provider's answer to this call, also when its
            // callback already moved the row (R-11-08); a later access e-mail that the provider accepts clears it.
            if (access) { accounts.markAccessEmailFailure(account.id(), account.email(), result.sent() ? null : clock.instant()); }
        });
        return id;
    }
    /** N-25 and N-27, the sign-in, recovery, access-resend and admin-welcome links; not N-25's `EMAIL_CHANGE` confirmation. */
    static boolean access(String code, Map<String, ?> variables) {
        return ACCESS_CODES.contains(code) && !"EMAIL_CHANGE".equals(variables.get("variant"));
    }
    /** A provider timeout or 5xx (the senders' `… timeout` and `… HTTP 5xx` errors); never a 4xx, a suppression or a refusal. */
    static boolean retryable(EmailSender.SendResult result) {
        return !result.sent() && result.error() != null && RETRYABLE.matcher(result.error()).find();
    }
}
