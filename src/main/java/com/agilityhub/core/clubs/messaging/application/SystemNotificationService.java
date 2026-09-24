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
        return deliver(UUID.randomUUID().toString(), code, accountId, variables);
    }
    public boolean completed(String id) {
        return notifications.findScoped(id).filter(item -> item.status() != Notification.Status.QUEUED).isPresent();
    }
    @Transactional(propagation = Propagation.NEVER)
    public String sendOnce(String id, String code, String accountId, Map<String, ?> variables) {
        if (completed(id)) { return id; }
        return deliver(id, code, accountId, variables);
    }
    @Transactional(propagation = Propagation.NEVER)
    public String sendOnceLocalized(String id,String code,String accountId,String locale,Map<String,?> variables) {
        if(completed(id)) return id;
        var account=accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return deliverTo(id,code,new NotificationAccounts.Recipient(account.id(),account.email(),locale==null?account.locale():locale,account.emailStatus()),variables);
    }
    private String deliver(String id, String code, String accountId, Map<String, ?> variables) {
        return deliverTo(id,code,accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)),variables);
    }
    /** {@link #sendOnce} with another copy (`variant`) of the same catalog code, e.g. the admins' N-01 (E3-T08). */
    @Transactional(propagation = Propagation.NEVER)
    public String sendOnceVariant(String id,String code,String variant,String accountId,Map<String,?> variables) {
        if(completed(id)) return id;
        return deliverTo(id,code,variant,accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)),variables);
    }
    @Transactional(propagation = Propagation.NEVER)
    public String sendApplicantOnce(String id,String code,String email,String locale,Map<String,?> variables) {
        return sendApplicantOnce(id,code,null,email,locale,variables);
    }
    @Transactional(propagation = Propagation.NEVER)
    public String sendApplicantOnce(String id,String code,String variant,String email,String locale,Map<String,?> variables) {
        if(completed(id)) return id;
        return deliverTo(id,code,variant,new NotificationAccounts.Recipient(null,email,locale,null),variables);
    }
    /**
     * The APP row and the SMS/PUSH intents only write rows (no network call), each in its own transaction: like the email
     * methods they refuse to run inside the caller's transaction, so a business transaction never joins them by mistake
     * (E5-T14). A consumer that must commit the rows together with its own write uses the `…InTransaction` variants.
     */
    @Transactional(propagation = Propagation.NEVER)
    public void appOnce(String id,String code,String accountId,Map<String,Object> variables) {
        appOnceVariant(id,code,null,accountId,variables);
    }
    /** {@link #appOnce} with another copy (`variant`) of the same catalog code, stored on the row (E3-T08: the N-01 copies). */
    @Transactional(propagation = Propagation.NEVER)
    public void appOnceVariant(String id,String code,String variant,String accountId,Map<String,Object> variables) {
        transactions.executeWithoutResult(tx -> app(id,code,variant,accountId,variables));
    }
    @Transactional(propagation = Propagation.NEVER)
    public void smsIntentOnce(String id,String code,String accountId,String locale,java.util.List<String> phones,
            String body,boolean enabled,Map<String,Object> variables) {
        transactions.executeWithoutResult(tx -> sms(id,code,accountId,locale,phones,body,enabled,variables));
    }
    /** A PUSH intent row (QUEUED, or SKIPPED_MODULE_OFF without PUSH); no push sender exists before E7. */
    @Transactional(propagation = Propagation.NEVER)
    public void pushIntentOnce(String id,String code,String accountId,String locale,boolean enabled,Map<String,Object> variables) {
        transactions.executeWithoutResult(tx -> push(id,code,accountId,locale,enabled,variables));
    }
    /**
     * {@link #appOnce} inside the caller's transaction, which must exist: the S08 N-15 rows commit together with the
     * entry's `offerNotifiedAt` (E5-T11/E5-T14). The same holds for the SMS and PUSH variants below.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appOnceInTransaction(String id,String code,String accountId,Map<String,Object> variables) { app(id,code,null,accountId,variables); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void smsIntentOnceInTransaction(String id,String code,String accountId,String locale,java.util.List<String> phones,
            String body,boolean enabled,Map<String,Object> variables) {
        sms(id,code,accountId,locale,phones,body,enabled,variables);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void pushIntentOnceInTransaction(String id,String code,String accountId,String locale,boolean enabled,Map<String,Object> variables) {
        push(id,code,accountId,locale,enabled,variables);
    }
    private void app(String id,String code,String variant,String accountId,Map<String,Object> variables) {
        if(notifications.findScoped(id).isPresent()) return;
        var account=accounts.find(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        notifications.queue(new Notification(id,TenantContext.require(),accountId,code,"APP",Notification.Status.SENT,null,clock.instant(),null,null,account.locale(),clock.instant(),variant));
        notifications.appContent(id,variables);
        events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued,TenantContext.require(),id,clock.instant()));
    }
    private void sms(String id,String code,String accountId,String locale,java.util.List<String> phones,String body,boolean enabled,Map<String,Object> variables) {
        if(notifications.findScoped(id).isPresent()) return;
        notifications.queue(new Notification(id,TenantContext.require(),accountId,code,"SMS",enabled?Notification.Status.QUEUED:Notification.Status.SKIPPED_MODULE_OFF,
                null,null,null,null,locale,clock.instant(),null));
        notifications.smsContent(id,phones,body,variables);
        events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued,TenantContext.require(),id,clock.instant()));
    }
    private void push(String id,String code,String accountId,String locale,boolean enabled,Map<String,Object> variables) {
        if(notifications.findScoped(id).isPresent()) return;
        notifications.queue(new Notification(id,TenantContext.require(),accountId,code,"PUSH",enabled?Notification.Status.QUEUED:Notification.Status.SKIPPED_MODULE_OFF,
                null,null,null,null,locale,clock.instant(),null));
        notifications.content(id,"PUSH",variables);
        events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued,TenantContext.require(),id,clock.instant()));
    }
    private String deliverTo(String id,String code,NotificationAccounts.Recipient account,Map<String,?> variables) {
        return deliverTo(id,code,null,account,variables);
    }
    private String deliverTo(String id,String code,String variant,NotificationAccounts.Recipient account,Map<String,?> variables) {
        String clubId = TenantContext.current();
        var settings = clubId == null ? new ClubEmailSettings.Settings("AgilityHub", null, "#2563eb", "#ffffff",
                platformFrom, "AgilityHub", null, "ca", parameters.defaultInteger("auth.magicLinkMinutes"))
                : clubs.get(clubId, platformFrom);
        Locale locale = Locale.forLanguageTag(account.locale() == null ? settings.defaultLocale() : account.locale());
        if (!messages.supports(locale)) { locale = Locale.forLanguageTag(settings.defaultLocale()); }
        var tags = new java.util.HashMap<String, String>();
        tags.put("notificationId", id);
        if (clubId != null) { tags.put("clubId", clubId); }
        var email = renderer.render(code, variant, account.email(), locale, variables, settings, tags);
        var notification = new Notification(id, clubId, account.id(), code, "EMAIL", Notification.Status.QUEUED,
                null, null, null, account.email(), locale.toLanguageTag(), clock.instant(), variant);
        transactions.executeWithoutResult(tx -> {
            if (notifications.findScoped(id).isEmpty()) {
                notifications.queue(notification);
                events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued, clubId, id, clock.instant()));
            }
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
