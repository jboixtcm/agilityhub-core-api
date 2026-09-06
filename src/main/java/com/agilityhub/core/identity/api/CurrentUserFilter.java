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
    public CurrentUserFilter(ImpersonationService impersonations) { this.impersonations = impersonations; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken bearer)) { chain.doFilter(request, response); return; }
        var jwt = bearer.getToken();
        CurrentUser.Impersonation impersonation = null;
        var origin = bearer.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")) ? DomainEvent.Origin.BACKOFFICE
                : bearer.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")) ? DomainEvent.Origin.INSTRUCTOR : DomainEvent.Origin.APP;
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
            if (request.getRequestURI().matches(".*/members/[^/]+/impersonation-token")) { impersonations.deny(jwt.getClaimAsString("actorAccountId")); }
            impersonation = impersonations.validate(jwt, request.getRequestURI().equals("/oauth2/revoke"));
            origin = DomainEvent.Origin.BACKOFFICE;
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        }
        try (var scope = CurrentUser.open(new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("name"), impersonation, origin))) {
            chain.doFilter(request, response);
        } finally { SecurityContextHolder.getContext().setAuthentication(authentication); }
    }
}
