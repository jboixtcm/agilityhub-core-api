package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.RateLimits.Route;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimits limits;
    private final SecurityEvents events;
    private final ApiExceptionHandler errors;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimits limits, SecurityEvents events, ApiExceptionHandler errors, ObjectMapper mapper) {
        this.limits = limits; this.events = events; this.errors = errors; this.mapper = mapper;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var jwt = authentication instanceof JwtAuthenticationToken token && token.isAuthenticated() ? token : null;
        Route route = route(request.getMethod(), path, jwt != null);
        if (route != null) {
            String subject = route == Route.ME ? jwt.getName() : request.getRemoteAddr();
            long retryAfter = limits.retryAfter(route, subject);
            if (retryAfter > 0) {
                events.record(SecurityEvents.Type.RATE_LIMITED, jwt == null ? null : jwt.getName(),
                        jwt == null ? null : jwt.getToken().getClaimAsString("clubId"));
                response.setStatus(ErrorCode.RATE_LIMITED.httpStatus());
                response.setContentType("application/json");
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Retry-After", Long.toString(retryAfter));
                mapper.writeValue(response.getOutputStream(), errors.body(new ApiException(ErrorCode.RATE_LIMITED), request));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Route route(String method, String path, boolean authenticated) {
        if (method.equals("OPTIONS")) { return null; }
        if (method.equals("POST") && (path.equals("/oauth2/token") || path.equals("/api/v1/auth/magic-link"))) { return Route.TOKEN; }
        if (method.equals("GET") && path.equals("/api/v1/branding")) { return Route.BRANDING; }
        if (path.equals("/api/v1/public") || path.startsWith("/api/v1/public/")) { return Route.PUBLIC; }
        if (authenticated && (path.equals("/api/v1/me") || path.startsWith("/api/v1/me/"))) { return Route.ME; }
        return null;
    }
}
