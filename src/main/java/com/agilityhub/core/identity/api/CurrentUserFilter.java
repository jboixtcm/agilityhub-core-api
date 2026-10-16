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
    private final ImpersonationService impersonations;
    private final com.agilityhub.core.identity.application.SupportAccessService supports;
    public CurrentUserFilter(ImpersonationService impersonations, com.agilityhub.core.identity.application.SupportAccessService supports) {
        this.impersonations = impersonations; this.supports = supports;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken bearer)) { chain.doFilter(request, response); return; }
        var jwt = bearer.getToken();
        CurrentUser.Impersonation impersonation = null;
        var origin = bearer.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")) ? DomainEvent.Origin.BACKOFFICE
                : bearer.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")) ? DomainEvent.Origin.INSTRUCTOR : DomainEvent.Origin.APP;
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
            // S16/S17 contract routes use an explicit denial. Existing platform delegates retain validate()'s denial.
            String path = request.getRequestURI();
            boolean console = path.equals("/api/v1/platform/club-templates")
                    || path.matches("/api/v1/platform/clubs(?:/[^/]+)?")
                    || path.matches("/api/v1/platform/clubs/[^/]+/(?:definition|domains|theme|modules|payment-providers|parameters|catalog-clone|admins|status|support-access|public-api-key|usage|checklist)(?:/.*)?");
            if (console || path.matches("/api/v1/platform/courses(?:/[^/]+)?")) {
                throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.IMPERSONATION_DENIED);
            }
            if (request.getRequestURI().matches(".*/members/[^/]+/impersonation-token")) { impersonations.deny(jwt.getClaimAsString("actorAccountId")); }
            impersonation = impersonations.validate(jwt, request.getRequestURI().equals("/oauth2/revoke"));
            origin = DomainEvent.Origin.BACKOFFICE;
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        }
        CurrentUser.Support support = null;
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("support"))) {
            // S17 R-17-10 (E10-T04): a support token opens the club's administration only while its grant lives, and never the
            // routes that hand over personal data or full bank accounts.
            String path = request.getRequestURI().substring(request.getContextPath().length());
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
