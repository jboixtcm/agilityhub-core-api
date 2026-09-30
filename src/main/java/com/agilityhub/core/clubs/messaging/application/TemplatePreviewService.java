package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.NotificationEmailRenderer;
import com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateRenderer;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateSampleData;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateValidator;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.SmsText;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * D9's [Vista prèvia] (S11 R-11-12, `POST /message-templates/{id}/preview`): the saved texts, or an unsaved draft, of one
 * club language rendered with the fictional data set of that language ({@link TemplateSampleData}) as the engine renders the
 * member's notice — title, body, the e-mail's subject and HTML (the club's layout), and the SMS after transliteration with its
 * length and segments. An unknown variable and an SMS over 160 characters are `warnings`, never errors (the variable renders
 * empty); ICU that does not parse is `400 TEMPLATE_SYNTAX_ERROR`. Writes nothing, except «envia prova» (`sendTest`): the same
 * rendering delivered once to the acting admin's own account — APP and its e-mail, never SMS and never anybody else — stored
 * in the log like any other notice (`dedupKey = test:{templateId}:{accountId}:{instant}`, audience `ADMINS`, no action).
 */
@Service
public class TemplatePreviewService {
    /** The fictional ids the preview's e-mail button links to (R-11-11 deep link of the code's action). */
    static final Map<String, String> SAMPLE_PARAMS = Map.of("dogId", "exemple", "classSessionId", "exemple", "bookingId", "exemple");
    private final MessageTemplateService templates; private final TemplateSampleData samples; private final TemplateRenderer renderer = new TemplateRenderer();
    private final NotificationEmailRenderer emails; private final ClubEmailSettings emailSettings; private final NotificationEngine engine;
    private final NotificationAccounts accounts; private final MessagingTransactions transactions; private final Clock clock; private final String platformFrom;

    public TemplatePreviewService(MessageTemplateService templates, IcuMessageSource messages, NotificationEmailRenderer emails, ClubEmailSettings emailSettings,
            NotificationEngine engine, NotificationAccounts accounts, MessagingTransactions transactions, Clock clock,
            @Value("${email.platform-from:}") String platformFrom) {
        this.templates = templates; this.samples = new TemplateSampleData(messages); this.emails = emails; this.emailSettings = emailSettings;
        this.engine = engine; this.accounts = accounts; this.transactions = transactions; this.clock = clock;
        this.platformFrom = platformFrom == null || platformFrom.isBlank() ? "no-reply@example.test" : platformFrom;
    }

    /** An unsaved draft of one language (`smsBody` optional). */
    public record Draft(String title, String body, String smsBody) { }
    public record Warning(String code, String variable) { }
    public record Preview(String title, String body, String emailSubject, String emailHtml, SmsText.Sms sms, List<Warning> warnings) { }

    public Preview preview(String id, String locale, Draft draft, boolean sendTest) {
        var config = templates.config();
        if (locale == null || !config.club().locales().contains(locale)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "locale")); }
        var template = templates.find(id);
        var rules = templates.rules(template.kind(), template.code(), template.category());
        String title = draft == null ? text(template.title(), locale, config) : draft.title();
        String body = draft == null ? text(template.body(), locale, config) : draft.body();
        String sms = draft == null ? (template.smsBody() == null ? null : text(template.smsBody(), locale, config)) : blankToNull(draft.smsBody());
        var unknown = new LinkedHashSet<String>();
        for (var field : new String[][] {{"title", title}, {"body", body}, {"smsBody", sms}}) {
            if (field[1] == null) { continue; }
            var names = TemplateValidator.syntax(field[0], field[1]);
            names.variables().stream().filter(name -> !rules.variables().contains(name)).forEach(unknown::add);
            names.icu().stream().filter(name -> !rules.variables().contains(name) && !rules.icuNames().contains(name)).forEach(unknown::add);
        }
        var language = Locale.forLanguageTag(locale);
        var values = samples.values(config, language);
        boolean transliterate = !Boolean.FALSE.equals(config.get("messaging.sms.transliterateToGsm7", Boolean.class));
        String code = template.code() == null ? "CUSTOM" : template.code();
        var rendered = renderer.render(code, title, body, sms, values, language, rules.variables(), transliterate);
        var warnings = new ArrayList<Warning>();
        unknown.forEach(name -> warnings.add(new Warning(ErrorCode.TEMPLATE_UNKNOWN_VARIABLE.name(), name)));
        if (rendered.sms() != null && rendered.sms().truncated()) { warnings.add(new Warning(ErrorCode.SMS_BODY_TOO_LONG.name(), null)); }
        var mail = emails.render(notification(template, config, locale, rendered, values, NotificationAudience.MEMBER, null, null, List.of()),
                values.getOrDefault("email", "").toString(), emailSettings.get(config.club().id(), platformFrom),
                app -> emailSettings.appOrigin(config.club().id(), app).orElse(null), unsubscribe(template, config), Map.of());
        if (sendTest) { sendTest(template, config, locale, rendered); }
        return new Preview(rendered.title(), rendered.body(), mail.subject(), mail.html(), rendered.sms(), warnings);
    }

    /** «envia prova»: one notice to the acting admin's own account, APP and e-mail only. */
    private void sendTest(MessageTemplate template, ClubConfig config, String locale, TemplateRenderer.Rendered rendered) {
        var user = CurrentUser.current();
        if (user == null) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        var now = clock.instant();
        var account = accounts.find(user.accountId()).orElse(null);
        var deliveries = new ArrayList<Notification.Delivery>();
        deliveries.add(new Notification.Delivery(NotificationChannel.APP, user.accountId(), DeliveryStatus.DELIVERED, 0, null, null, null, null, now, null));
        boolean mailable = account != null && account.email() != null && !account.email().isBlank() && account.emailStatus() == null;
        deliveries.add(mailable ? new Notification.Delivery(NotificationChannel.EMAIL, account.email(), DeliveryStatus.QUEUED, 0, now, null, null, null, null, null)
                : new Notification.Delivery(NotificationChannel.EMAIL, null, DeliveryStatus.SKIPPED_NO_CONTACT, 0, null, null, null, null, null, null));
        var recipient = new Notification.Recipient(user.accountId(), null, null, null, user.name() == null ? "" : user.name());
        String dedupKey = "test:" + template.id() + ":" + user.accountId() + ":" + now;
        // The fictional values are not stored: the frozen texts are the notice (and no credential link is ever kept, R-14-18).
        var notification = notification(template, config, locale, new TemplateRenderer.Rendered(rendered.title(), rendered.body(), null), Map.of(),
                NotificationAudience.ADMINS, recipient, dedupKey, deliveries);
        transactions.run(() -> engine.deliver(List.of(notification)));
    }

    /**
     * The notice as the engine would store it: a `CUSTOM` template is sent under N-24, the only code that carries one (S11 §7,
     * R-11-13), in its own category. The preview's copy links its button to fictional ids; the test send has no action.
     */
    private Notification notification(MessageTemplate template, ClubConfig config, String locale, TemplateRenderer.Rendered rendered, Map<String, Object> values,
            NotificationAudience audience, Notification.Recipient recipient, String dedupKey, List<Notification.Delivery> deliveries) {
        String code = template.kind() == TemplateKind.CATALOG ? template.code() : "N-24";
        var type = template.kind() == TemplateKind.CATALOG && dedupKey == null ? MessageTemplateService.spec(template.code()).action(NotificationAudience.MEMBER) : null;
        var action = type == null ? null : new Notification.Action(type, SAMPLE_PARAMS);
        String id = UUID.randomUUID().toString();
        return new Notification(id, config.club().id(), code, template.category(), template.id(), template.version(), null, null, dedupKey == null ? id : dedupKey,
                audience, recipient, locale, new Notification.Subject(null, null, null, null, null, null, null, null, null), template.icon(), template.color(),
                rendered.title(), rendered.body(), rendered.sms() == null ? null : rendered.sms().text(), action, deliveries, null, clock.instant(),
                null, null, null, null, null, null, null, null, values);
    }
    private String unsubscribe(MessageTemplate template, ClubConfig config) {
        if (template.category() != NotificationCategory.CLUB_NEWS) { return null; }
        return emailSettings.appOrigin(config.club().id()).map(origin -> origin + "/comunicats/baixa?t=exemple").orElse(null);
    }
    private static String text(LocalizedText text, String locale, ClubConfig config) {
        String exact = text.values().get(locale);
        if (exact != null && !exact.isBlank()) { return exact; }
        return text.withDefaultLocale(config.club().defaultLocale()).resolve(config.club().defaultLocale()).value();
    }
    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text; }
}
