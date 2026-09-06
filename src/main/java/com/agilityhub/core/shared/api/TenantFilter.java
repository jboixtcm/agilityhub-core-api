package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantHostResolver;
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

/** Runs after authentication and before idempotency/MVC; every exit removes ThreadLocal state. */
public class TenantFilter extends OncePerRequestFilter {
    private final TenantHostResolver hosts;
    private final boolean local;
    private final ApiExceptionHandler errors;
    private final ObjectMapper mapper;
    public TenantFilter(TenantHostResolver hosts, boolean local, ApiExceptionHandler errors, ObjectMapper mapper) {
        this.hosts = hosts; this.local = local; this.errors = errors; this.mapper = mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContext.clear();
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean global = path.equals("/oauth2/jwks") || path.equals("/.well-known/jwks.json") || path.startsWith("/v3/api-docs") || path.startsWith("/actuator/") || path.equals("/api/v1/health") || path.equals("/api/v1/platform") || path.startsWith("/api/v1/platform/");
            boolean publicRoute = path.equals("/api/v1/branding") || path.equals("/api/v1/manifest.webmanifest")
                    || path.startsWith("/api/v1/public/") || path.startsWith("/oauth2/");
            if (!global) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                boolean authenticatedJwt = authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated();
                if (publicRoute || authenticatedJwt) {
                    String host = local && request.getHeader("X-Club-Host") != null
                            ? request.getHeader("X-Club-Host") : request.getHeader("Host");
                    var hostClub = hosts.resolve(host);
                    String clubId;
                    if (authenticatedJwt) {
                        clubId = ((JwtAuthenticationToken) authentication).getToken().getClaimAsString("clubId");
                        if (clubId == null || clubId.isBlank()) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); }
                        if (hostClub.isPresent() && !hostClub.get().equals(clubId)) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
                    } else { clubId = hostClub.orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_HOST)); }
                    try (var scope = TenantContext.open(clubId)) { chain.doFilter(request, response); }
                    return;
                }
            }
            chain.doFilter(request, response);
        } catch (ApiException exception) {
            response.setStatus(exception.code().httpStatus()); response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), errors.body(exception, request));
        } finally { TenantContext.clear(); }
    }
}
