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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        traceId(request);
        chain.doFilter(request, response);
    }
}
