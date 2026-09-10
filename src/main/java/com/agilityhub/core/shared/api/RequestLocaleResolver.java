package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.LocaleSettingsProvider;
import com.agilityhub.core.shared.application.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.LocaleResolver;

public final class RequestLocaleResolver implements LocaleResolver {
    private static final String ATTRIBUTE = RequestLocaleResolver.class.getName();
    private final IcuMessageSource messages;
    private final LocaleSettingsProvider clubs;

    public RequestLocaleResolver(IcuMessageSource messages, LocaleSettingsProvider clubs) {
        this.messages = messages; this.clubs = clubs;
    }

    @Override public Locale resolveLocale(HttpServletRequest request) {
        if (HealthRequests.matches(request)) { return LocaleContext.DEFAULT; }
        if (request.getAttribute(ATTRIBUTE) instanceof Locale locale) { return locale; }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String clubId = TenantContext.current();
        if (clubId == null && authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            clubId = jwt.getToken().getClaimAsString("clubId");
        }
        var settings = clubId == null || clubId.isBlank() ? null : clubs.settings(clubId).orElse(null);
        Locale fallback = settings == null || settings.defaultLocale() == null ? LocaleContext.DEFAULT
                : Locale.forLanguageTag(settings.defaultLocale());
        if (!messages.supports(fallback)) { fallback = LocaleContext.DEFAULT; }
        Locale result = null;
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            String accountLocale = jwt.getToken().getClaimAsString("locale");
            if (accountLocale != null && !accountLocale.isBlank()) {
                var preferred = Locale.forLanguageTag(accountLocale);
                result = messages.supports(preferred) ? preferred : fallback;
            }
        }
        if (result == null) {
            List<Locale> allowed = settings == null ? messages.supportedLocales().stream().sorted(
                    java.util.Comparator.comparing(Locale::toLanguageTag)).toList()
                    : settings.locales().stream().map(Locale::forLanguageTag).filter(messages::supports).toList();
            String header = request.getHeader("Accept-Language");
            if (header != null) {
                try {
                    // Filtering honors weights, regional tags, wildcards and explicit q=0 exclusions.
                    var ranges = Locale.LanguageRange.parse(header);
                    result = Locale.lookup(ranges, allowed);
                    if (result == null) {
                        var matches = Locale.filter(ranges, allowed);
                        result = matches.isEmpty() ? null : matches.getFirst();
                    }
                } catch (IllegalArgumentException malformedHeader) {
                    result = fallback;
                }
            }
        }
        if (result == null) { result = fallback; }
        request.setAttribute(ATTRIBUTE, result);
        return result;
    }

    @Override public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("Change the account locale or Accept-Language instead");
    }
}
