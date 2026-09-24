package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ClubFormats;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Fixed product copy only; no MessageTemplate or preference lookup for SYSTEM messages. */
public final class SystemEmailRenderer {
    private final IcuMessageSource messages;
    private final TemplateEngine templates;
    private final ClubFormats formats;
    public SystemEmailRenderer(IcuMessageSource messages, TemplateEngine templates) {
        this.messages = messages; this.templates = templates; this.formats = new ClubFormats(ZoneOffset.UTC, messages);
    }
    public EmailMessage render(String code, String to, Locale locale, Map<String, ?> variables,
                               ClubEmailSettings.Settings settings, Map<String, String> tags) {
        return render(code, null, to, locale, variables, settings, tags);
    }
    /**
     * `variant` selects another copy of the same catalog notification (`notif.{code}.{variant}.title|body`), e.g. the
     * admins' N-01 (E3-T08, M9); the notification row keeps the catalog code.
     */
    public EmailMessage render(String code, String variant, String to, Locale locale, Map<String, ?> variables,
                               ClubEmailSettings.Settings settings, Map<String, String> tags) {
        if (!Set.of("N-01", "N-02", "N-03", "N-08a", "N-08b", "N-32a", "N-32b", "N-32c", "N-32d", "N-25", "N-26", "N-27", "N-39", "N-42", "N-36", "N-40", "N-47", "N-16", "N-17", "N-54").contains(code)) { throw new ApiException(ErrorCode.TEMPLATE_NOT_SENDABLE); }
        String link = null;
        String expiry = null;
        if (Set.of("N-02","N-25","N-27","N-39").contains(code)) {
            Object value = variables.get("link");
            try {
                var uri = URI.create(value instanceof String text ? text : "");
                if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException();
                }
                link = uri.toASCIIString();
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "link"));
            }
            expiry = messages.format("email.layout.expiry", Map.of("duration",
                    formats.formatDuration(Duration.ofMinutes(Set.of("N-02","N-39").contains(code) && variables.get("expires_minutes") instanceof Number minutes ? minutes.longValue() : settings.magicLinkMinutes()), locale)), locale);
        }
        String key = "notif." + code + (variant == null ? "" : "." + variant);
        String title = messages.format(key + ".title", variables, locale);
        String body = messages.format(key + ".body", variables, locale);
        String action = messages.format("email.layout.signIn", Map.of(), locale);
        String footer = messages.format("email.layout.system", Map.of(), locale);
        var context = new Context(locale);
        var values = new java.util.HashMap<String, Object>();
        values.put("title", title); values.put("body", body); values.put("action", action); values.put("footer", footer);
        values.put("link", link); values.put("expiry", expiry); values.put("brand", settings.brand());
        values.put("logo", safeLogo(settings.logoUrl())); values.put("primary", color(settings.primary(), "#2563eb"));
        values.put("onPrimary", color(settings.onPrimary(), "#ffffff")); values.put("language", locale.toLanguageTag());
        context.setVariables(values);
        String html = templates.process("email/system", context);
        String text = title + "\n\n" + body + (link == null ? "" : "\n\n" + action + ": " + link + "\n" + expiry)
                + "\n\n" + settings.brand() + "\n" + footer;
        return new EmailMessage(to, title, html, text, new EmailMessage.Address(settings.fromAddress(), settings.fromName()),
                settings.replyTo(), locale, tags);
    }
    private String color(String color, String fallback) { return color != null && color.matches("#[0-9a-fA-F]{6}") ? color : fallback; }
    private String safeLogo(String logo) {
        if (logo == null) { return null; }
        try { var uri = URI.create(logo); return "https".equals(uri.getScheme()) && uri.getHost() != null ? logo : null; }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
