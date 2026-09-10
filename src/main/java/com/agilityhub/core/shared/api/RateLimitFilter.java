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
    private com.agilityhub.core.shared.application.TenantHostResolver hosts;
    private boolean local;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.shared.application.SignupCapabilities signupCapabilities;
    public void signupHosts(com.agilityhub.core.shared.application.TenantHostResolver hosts, boolean local) { this.hosts=hosts;this.local=local; }

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
            String clubId = jwt == null ? null : jwt.getToken().getClaimAsString("clubId");
            if (route.name().startsWith("SIGNUP_")) {
                if (clubId == null && hosts != null) clubId=hosts.resolve(local && request.getHeader("X-Club-Host")!=null?request.getHeader("X-Club-Host"):request.getHeader("Host")).orElse(null);
                subject=java.util.Objects.toString(clubId,"unknown")+":"+request.getRemoteAddr();
            }
            long retryAfter = limits.retryAfter(route, subject);
            if (route == Route.SIGNUP_SUBMIT) retryAfter=Math.max(retryAfter,limits.retryAfter(Route.SIGNUP_DAILY,subject));
            if (retryAfter > 0) {
                events.record(SecurityEvents.Type.RATE_LIMITED, jwt == null ? null : jwt.getName(),
                        clubId);
                if(route.name().startsWith("SIGNUP_")&&signupCapabilities!=null) org.slf4j.LoggerFactory.getLogger(RateLimitFilter.class)
                        .info("Signup rate limit traceId={} clubId={} ipHash={}",RequestTraceFilter.traceId(request),clubId,signupCapabilities.fingerprint(request.getRemoteAddr()));
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
        if (method.equals("POST")) {
            Route signup=switch(path) {
                case "/api/v1/signup/identity-checks" -> Route.SIGNUP_IDENTITY;
                case "/api/v1/signup/family-group-lookups" -> Route.SIGNUP_FAMILY;
                case "/api/v1/signup/upload-urls" -> Route.SIGNUP_UPLOAD;
                case "/api/v1/signup" -> Route.SIGNUP_SUBMIT;
                case "/api/v1/checkout-sessions" -> authenticated?null:Route.SIGNUP_CHECKOUT;
                default -> null;
            };
            if(signup!=null) return signup;
        }
        if(method.equals("GET")&&path.equals("/api/v1/signup/towns")) return Route.SIGNUP_TOWNS;
        if (method.equals("POST") && (path.equals("/oauth2/token") || path.equals("/api/v1/auth/magic-link"))) { return Route.TOKEN; }
        if (method.equals("GET") && path.equals("/api/v1/branding")) { return Route.BRANDING; }
        if (path.equals("/api/v1/public") || path.startsWith("/api/v1/public/")) { return Route.PUBLIC; }
        if (authenticated && (path.equals("/api/v1/me") || path.startsWith("/api/v1/me/"))) { return Route.ME; }
        return null;
    }
}
