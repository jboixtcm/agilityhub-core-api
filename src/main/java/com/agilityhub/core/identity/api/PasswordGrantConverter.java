package com.agilityhub.core.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationConverter;

public final class PasswordGrantConverter implements AuthenticationConverter {
    @Override public Authentication convert(HttpServletRequest request) {
        String grant = request.getParameter("grant_type");
        if (!java.util.Set.of("password", "refresh_token", "authorization_code", com.agilityhub.core.identity.application.MagicLinkService.GRANT,
                com.agilityhub.core.identity.application.HandoffService.GRANT).contains(java.util.Objects.toString(grant, ""))) { return null; }
        required(request, "grant_type");
        for (String name : request.getParameterMap().keySet()) { if (request.getParameterValues(name).length != 1) { throw invalid(); } }
        String client = request.getParameter("client_id");
        String secret = request.getParameter("client_secret");
        String header = request.getHeader("Authorization");
        if (header != null) {
            if (!header.startsWith("Basic ") || client != null || secret != null) { throw invalid(); }
            try {
                String decoded = new String(java.util.Base64.getDecoder().decode(header.substring(6)), java.nio.charset.StandardCharsets.UTF_8);
                int split = decoded.indexOf(':');
                if (split < 1) { throw invalid(); }
                client = java.net.URLDecoder.decode(decoded.substring(0, split), java.nio.charset.StandardCharsets.UTF_8);
                secret = java.net.URLDecoder.decode(decoded.substring(split + 1), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) { throw invalid(); }
        }
        if (client == null && !"authorization_code".equals(grant) && !com.agilityhub.core.identity.application.HandoffService.GRANT.equals(grant)) { client = "clubs-app"; }
        if (client == null || client.isBlank()) { throw invalid(); }
        var authentication = new PasswordGrantAuthenticationToken(grant, client,
                "password".equals(grant) ? required(request, "username") : null,
                required(request, switch (grant) { case "password" -> "password"; case "refresh_token" -> "refresh_token"; case "authorization_code" -> "code"; default -> "token"; }));
        authentication.oidc(secret, request.getParameter("scope"), "authorization_code".equals(grant) ? required(request, "redirect_uri") : null, request.getParameter("code_verifier"));
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
