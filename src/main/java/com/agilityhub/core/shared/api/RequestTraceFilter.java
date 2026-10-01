package com.agilityhub.core.shared.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";

    public static String traceId(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) == null) {
            String tracingId = MDC.get("traceId");
            request.setAttribute(ATTRIBUTE, tracingId == null ? UUID.randomUUID().toString() : tracingId);
        }
        return (String) request.getAttribute(ATTRIBUTE);
    }

    /** Enrich only from verified authentication / tenant resolution, never request headers or unsigned JWTs. */
    public static void identity(String accountId, String clubId) {
        if (accountId != null) { MDC.put("accountId", accountId); }
        if (clubId != null) { MDC.put("clubId", clubId); }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var previous = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("traceId", traceId(request));
        MDC.put("clubId", "-");
        MDC.put("accountId", "-");
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) { MDC.clear(); } else { MDC.setContextMap(previous); }
        }
    }
}
