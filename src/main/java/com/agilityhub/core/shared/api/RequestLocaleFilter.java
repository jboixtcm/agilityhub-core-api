package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.LocaleContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** After tenant resolution and before idempotency, with the same resolver used by MVC and early errors. */
public final class RequestLocaleFilter extends OncePerRequestFilter {
    private final RequestLocaleResolver locales;
    public RequestLocaleFilter(RequestLocaleResolver locales) { this.locales = locales; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return HealthRequests.matches(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var locale = locales.resolveLocale(request);
        response.setHeader("Content-Language", locale.toLanguageTag());
        try (var scope = LocaleContext.open(locale)) { chain.doFilter(request, response); }
    }
}
