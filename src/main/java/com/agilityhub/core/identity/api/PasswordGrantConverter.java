package com.agilityhub.core.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationConverter;

public final class PasswordGrantConverter implements AuthenticationConverter {
    @Override public Authentication convert(HttpServletRequest request) {
        String grant = request.getParameter("grant_type");
        if (!"password".equals(grant) && !"refresh_token".equals(grant) && !com.agilityhub.core.identity.application.MagicLinkService.GRANT.equals(grant) && !com.agilityhub.core.identity.application.HandoffService.GRANT.equals(grant)) { return null; }
        if (request.getHeader("Authorization") != null || request.getParameter("client_secret") != null
                || request.getParameter("scope") != null) { throw invalid(); }
        required(request, "grant_type");
        String client = request.getParameter("client_id") == null && !com.agilityhub.core.identity.application.HandoffService.GRANT.equals(grant) ? "clubs-app" : required(request, "client_id");
        var authentication = new PasswordGrantAuthenticationToken(grant, client,
                "password".equals(grant) ? required(request, "username") : null,
                required(request, "password".equals(grant) ? "password" : "refresh_token".equals(grant) ? "refresh_token" : "token"));
        authentication.userAgent(request.getHeader("User-Agent"));
        return authentication;
    }
    private String required(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1 || values[0].isBlank()) { throw invalid(); }
        return values[0];
    }
    private OAuth2AuthenticationException invalid() { return new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST); }
}
