package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.AccountAccess;
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

/** Live account denial is checked on every bearer request, including routes outside identity. */
public final class AccountStateFilter extends OncePerRequestFilter {
    private final AccountAccess accounts;
    private final ApiExceptionHandler errors;
    private final ObjectMapper mapper;
    public AccountStateFilter(AccountAccess accounts, ApiExceptionHandler errors, ObjectMapper mapper) {
        this.accounts = accounts; this.errors = errors; this.mapper = mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return HealthRequests.matches(request);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()
                && accounts.blocked(jwt.getName(), jwt.getToken().getIssuedAt())) {
            response.setStatus(ErrorCode.ACCOUNT_BLOCKED.httpStatus()); response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), errors.body(new ApiException(ErrorCode.ACCOUNT_BLOCKED), request));
            return;
        }
        chain.doFilter(request, response);
    }
}
