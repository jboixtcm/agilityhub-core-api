package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.ImpersonationService;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.domain.DomainEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class CurrentUserFilter extends OncePerRequestFilter {
    /**
     * S17 R-17-10 (E10-T04 round 2): the routes are matched on the path Spring routes (decoded, without `;` parameters), as
     * `RateLimitFilter` matches them, so `/members/%65xport` is the members' export here too.
     */
    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();
    private final ImpersonationService impersonations;
    private final com.agilityhub.core.identity.application.SupportAccessService supports;
    public CurrentUserFilter(ImpersonationService impersonations, com.agilityhub.core.identity.application.SupportAccessService supports) {
        this.impersonations = impersonations; this.supports = supports;
    }
    /** Deny every other method and path before a keyed response can be replayed. */
    static boolean bookingOperation(String method, String path) {
        return switch (method) {
            case "GET" -> java.util.Set.of("/api/v1/me", "/api/v1/me/home", "/api/v1/me/bookable-classes", "/api/v1/me/training-slots",
                    "/api/v1/me/training-summary", "/api/v1/club-pages/training-conditions").contains(path)
                    || (path.matches("/api/v1/(bookings|waitlist-entries|training-bookings)/[A-Za-z0-9_-]+")
                    && !path.endsWith("/filter-values") && !path.endsWith("/export"));
            case "POST" -> java.util.Set.of("/api/v1/seat-holds", "/api/v1/bookings", "/api/v1/waitlist-entries", "/api/v1/training-bookings", "/oauth2/revoke").contains(path)
                    || path.matches("/api/v1/(bookings|training-bookings|waitlist-entries)/[A-Za-z0-9_-]+/cancellation")
                    || path.matches("/api/v1/waitlist-entries/[A-Za-z0-9_-]+/claim");
            case "DELETE" -> path.matches("/api/v1/seat-holds/[A-Za-z0-9_-]+");
            default -> false;
        };
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken bearer)) { chain.doFilter(request, response); return; }
        var jwt = bearer.getToken();
        CurrentUser.Impersonation impersonation = null;
        var origin = bearer.getAuthorities().stream().anyMatch(role -> "ROLE_ADMIN".equals(role.getAuthority())) ? DomainEvent.Origin.BACKOFFICE
                : bearer.getAuthorities().stream().anyMatch(role -> "ROLE_INSTRUCTOR".equals(role.getAuthority())) ? DomainEvent.Origin.INSTRUCTOR : DomainEvent.Origin.APP;
        String path = PATHS.getPathWithinApplication(request);
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
            // S16/S17 contract routes use an explicit denial. Existing platform delegates retain validate()'s denial.
            boolean console = path.equals("/api/v1/platform/club-templates")
                    || path.matches("/api/v1/platform/clubs(?:/[^/]+)?")
                    || path.matches("/api/v1/platform/clubs/[^/]+/(?:definition|domains|theme|modules|payment-providers|parameters|catalog-clone|admins|status|support-access|public-api-key|usage|checklist|migrations)(?:/.*)?");
            if (console || path.matches("/api/v1/platform/courses(?:/[^/]+)?")) {
                throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.IMPERSONATION_DENIED);
            }
            if (path.matches(".*/members/[^/]+/impersonation-token")) { impersonations.deny(jwt.getClaimAsString("actorAccountId")); }
            impersonation = impersonations.validate(jwt, path.equals("/oauth2/revoke"));
            if (impersonation.bookingsOnly() && !bookingOperation(request.getMethod(), path)) {
                impersonations.deny(impersonation.actorAccountId());
            }
            origin = impersonation.bookingsOnly() ? DomainEvent.Origin.INSTRUCTOR : DomainEvent.Origin.BACKOFFICE;
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        }
        CurrentUser.Support support = null;
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("support"))) {
            // S17 R-17-10 (E10-T04): a support token opens the club's administration only while its grant lives, and never the
            // routes that hand over personal data or full bank accounts, or schedule or cancel a member's erasure (E11-T16). Their
            // handlers refuse it too (@SupportRestricted; SecurityInventoryIT checks both lists are the same).
            support = supports.validate(jwt, path.equals("/oauth2/revoke"));
            if (com.agilityhub.core.identity.application.SupportAccessService.restricted(request.getMethod(), path)) {
                throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.SUPPORT_RESTRICTED);
            }
        }
        try (var scope = CurrentUser.open(new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("name"), impersonation, origin, support))) {
            chain.doFilter(request, response);
        } finally { SecurityContextHolder.getContext().setAuthentication(authentication); }
    }
}
