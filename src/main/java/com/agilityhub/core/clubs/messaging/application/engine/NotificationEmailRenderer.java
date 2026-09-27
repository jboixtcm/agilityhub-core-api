package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.EmailMessage;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * S11 R-11-05 (5) and R-11-08, the e-mail of an engine notification: subject = the rendered title; HTML = the club's
 * Thymeleaf layout (logo, colours and name of `Club.theme`, the E1-T03 layout) with the frozen body, a button to the feed
 * of the club app when the code has a native action (R-11-11: the action opens from there), the calendar link of N-04, the
 * «reply to contact the club» footer, and — only on `CLUB_NEWS` — the «Deixar de rebre aquests comunicats» link with the
 * `List-Unsubscribe` header; plus the plain-text part. `From`/`Reply-To` come from {@link ClubEmailSettings} (the club
 * domain only when S17 verified it).
 */
public final class NotificationEmailRenderer {
    private final IcuMessageSource messages; private final TemplateEngine templates;

    public NotificationEmailRenderer(IcuMessageSource messages, TemplateEngine templates) { this.messages = messages; this.templates = templates; }

    /**
     * @param appOrigin   `https://{club app host}`, or null when the club has no verified app domain (no links then)
     * @param unsubscribe the signed unsubscribe URL of a `CLUB_NEWS` mail, otherwise null
     */
    public EmailMessage render(Notification notification, String to, ClubEmailSettings.Settings settings, String appOrigin, String unsubscribe,
            Map<String, String> tags) {
        var locale = Locale.forLanguageTag(notification.locale() == null ? settings.defaultLocale() : notification.locale());
        boolean news = notification.category() == NotificationCategory.CLUB_NEWS;
        String link = notification.action() != null && appOrigin != null ? appOrigin + "/notificacions" : null;
        String calendar = notification.variables() != null && notification.variables().get("calendar_links") instanceof String ics && safe(ics) ? ics : null;
        String unsubscribeLink = news && unsubscribe != null && safe(unsubscribe) ? unsubscribe : null;
        var values = new HashMap<String, Object>();
        values.put("title", notification.title()); values.put("paragraphs", Arrays.asList(notification.body() == null ? new String[0] : notification.body().split("\\R+")));
        values.put("brand", settings.brand()); values.put("logo", safeLogo(settings.logoUrl()));
        values.put("primary", color(settings.primary(), "#2563eb")); values.put("onPrimary", color(settings.onPrimary(), "#ffffff"));
        values.put("language", locale.toLanguageTag());
        values.put("link", link); values.put("action", messages.format("email.layout.open", Map.of(), locale));
        values.put("calendar", calendar); values.put("calendarLabel", messages.format("email.layout.calendar", Map.of(), locale));
        values.put("reply", messages.format("email.layout.reply", Map.of(), locale));
        values.put("unsubscribe", unsubscribeLink); values.put("unsubscribeLabel", messages.format("email.layout.unsubscribe", Map.of(), locale));
        var context = new Context(locale); context.setVariables(values);
        String html = templates.process("email/notification", context);
        var text = new StringBuilder(notification.title()).append("\n\n").append(notification.body() == null ? "" : notification.body());
        if (link != null) { text.append("\n\n").append(values.get("action")).append(": ").append(link); }
        if (calendar != null) { text.append("\n").append(values.get("calendarLabel")).append(": ").append(calendar); }
        text.append("\n\n").append(settings.brand()).append("\n").append(values.get("reply"));
        if (unsubscribeLink != null) { text.append("\n").append(values.get("unsubscribeLabel")).append(": ").append(unsubscribeLink); }
        var headers = new LinkedHashMap<String, String>();
        if (unsubscribeLink != null) { headers.put("List-Unsubscribe", "<" + unsubscribeLink + ">"); }
        return new EmailMessage(to, notification.title(), html, text.toString(), new EmailMessage.Address(settings.fromAddress(), settings.fromName()),
                settings.replyTo(), locale, tags, headers);
    }

    private static boolean safe(String url) {
        try { var uri = URI.create(url); return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static String color(String color, String fallback) { return color != null && color.matches("#[0-9a-fA-F]{6}") ? color : fallback; }
    private static String safeLogo(String logo) { return logo != null && safe(logo) ? logo : null; }
}
