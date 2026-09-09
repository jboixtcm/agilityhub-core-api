package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.TokenDelivery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

/** Browser transport only; refresh ownership and rotation remain in TokenService. */
@Component
public class RefreshCookies {
    public static final String NAME = "ah_refresh";
    private final RegisteredClientRepository clients;
    private final boolean local;

    public RefreshCookies(RegisteredClientRepository clients, Environment environment) {
        this.clients = clients;
        local = environment.acceptsProfiles(Profiles.of("local"))
                && !environment.acceptsProfiles(Profiles.of("staging", "prod"));
    }
    public boolean cookieClient(String clientId) {
        return clientId != null && TokenDelivery.cookie(clients.findByClientId(clientId));
    }
    public boolean cookieRefresh(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getRequestURI().equals("/oauth2/token")
                && "refresh_token".equals(request.getParameter("grant_type")) && request.getParameter("refresh_token") == null;
    }
    public String read(HttpServletRequest request) {
        // An explicit registered browser client is required, even though password grants have a legacy default.
        if (!cookieClient(request.getParameter("client_id")) || !sameHost(request) || request.getCookies() == null) { throw expired(); }
        var matches = Arrays.stream(request.getCookies()).filter(cookie -> NAME.equals(cookie.getName())).toList();
        if (matches.size() != 1 || matches.getFirst().getValue().isBlank()) { throw expired(); }
        return matches.getFirst().getValue();
    }
    private boolean sameHost(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String source = origin == null ? request.getHeader("Referer") : origin;
        if (source == null || request.getHeader("Host") == null) { return false; }
        try {
            URI uri = URI.create(source);
            URI target = URI.create("https://" + request.getHeader("Host"));
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && (origin == null || uri.getRawPath().isEmpty() && uri.getQuery() == null)
                    && uri.getHost().equalsIgnoreCase(target.getHost());
        } catch (IllegalArgumentException malformed) { return false; }
    }
    public void issue(HttpServletRequest request, HttpServletResponse response, OAuth2RefreshToken token) {
        response.addHeader("Set-Cookie", header(request, token.getTokenValue(),
                Duration.between(token.getIssuedAt(), token.getExpiresAt()).getSeconds()));
    }
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Set-Cookie", clearHeader(request));
    }
    public String clearHeader(HttpServletRequest request) { return header(request, "", 0); }
    private String header(HttpServletRequest request, String value, long seconds) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(!local || request.isSecure())
                .sameSite("Strict").path("/oauth2/token").maxAge(seconds).build().toString();
    }
    private static ApiException expired() { return new ApiException(ErrorCode.REFRESH_EXPIRED); }
}
