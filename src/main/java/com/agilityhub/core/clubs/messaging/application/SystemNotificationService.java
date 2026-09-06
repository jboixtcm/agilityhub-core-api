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
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

public class SystemNotificationService {
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
        var account = accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        String clubId = TenantContext.current();
        var settings = clubId == null ? new ClubEmailSettings.Settings("AgilityHub", null, "#2563eb", "#ffffff",
                platformFrom, "AgilityHub", null, "ca", parameters.defaultInteger("auth.magicLinkMinutes"))
                : clubs.get(clubId, platformFrom);
        Locale locale = Locale.forLanguageTag(account.locale() == null ? settings.defaultLocale() : account.locale());
        if (!messages.supports(locale)) { locale = Locale.forLanguageTag(settings.defaultLocale()); }
        String id = UUID.randomUUID().toString();
        var tags = new java.util.HashMap<String, String>();
        tags.put("notificationId", id);
        if (clubId != null) { tags.put("clubId", clubId); }
        var email = renderer.render(code, account.email(), locale, variables, settings, tags);
        var notification = new Notification(id, clubId, accountId, code, "EMAIL", Notification.Status.QUEUED,
                null, null, null, account.email(), locale.toLanguageTag(), clock.instant());
        transactions.executeWithoutResult(tx -> {
            notifications.queue(notification);
            events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued, clubId, id, clock.instant()));
        });
        var result = account.emailStatus() == null ? sender.send(email) : EmailSender.SendResult.failed("Recipient email suppressed");
        transactions.executeWithoutResult(tx -> {
            if (notifications.finish(id, result.sent() ? Notification.Status.SENT : Notification.Status.FAILED,
                    result.providerMessageId(), result.error(), clock.instant())) {
                events.publish(new NotificationEvent(result.sent() ? NotificationEvent.Kind.NotificationSent : NotificationEvent.Kind.NotificationFailed,
                        clubId, id, clock.instant()));
            }
        });
        return id;
    }
}
