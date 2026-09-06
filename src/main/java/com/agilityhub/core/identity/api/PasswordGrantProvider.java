package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.shared.domain.ApiException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

public final class PasswordGrantProvider implements AuthenticationProvider {
    private final TokenService tokens;
    private final RegisteredClientRepository clients;
    public PasswordGrantProvider(TokenService tokens, RegisteredClientRepository clients) { this.tokens = tokens; this.clients = clients; }
    @Override public Authentication authenticate(Authentication authentication) {
        var grant = (PasswordGrantAuthenticationToken) authentication;
        try {
            var client = clients.findByClientId(grant.clientId());
            if (client == null || !client.getAuthorizationGrantTypes().contains(grant.getGrantType())) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            var issued = switch (grant.getGrantType().getValue()) {
                case "password" -> tokens.password(grant.username(), (String) grant.getCredentials(), grant.clientId(), grant.userAgent());
                case "refresh_token" -> tokens.refresh((String) grant.getCredentials(), grant.clientId());
                default -> tokens.magicLink((String) grant.getCredentials(), grant.clientId(), grant.userAgent());
            };
            var jwt = issued.access();
            var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt());
            return new OAuth2AccessTokenAuthenticationToken(client, (Authentication) grant.getPrincipal(), access, issued.refresh());
        } catch (ApiException failure) {
            throw new OAuth2AuthenticationException(new OAuth2Error(failure.code().name()), failure);
        } finally { grant.eraseCredentials(); }
    }
    @Override public boolean supports(Class<?> type) { return PasswordGrantAuthenticationToken.class.isAssignableFrom(type); }
}
