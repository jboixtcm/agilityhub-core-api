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
    private final com.agilityhub.core.identity.application.HandoffService handoffs;
    private final com.agilityhub.core.identity.application.OidcService oidc;
    public PasswordGrantProvider(TokenService tokens, RegisteredClientRepository clients, com.agilityhub.core.identity.application.HandoffService handoffs, com.agilityhub.core.identity.application.OidcService oidc) {
        this.oidc = oidc; this.tokens = tokens; this.clients = clients; this.handoffs = handoffs;
    }
    @Override public Authentication authenticate(Authentication authentication) {
        var grant = (PasswordGrantAuthenticationToken) authentication;
        try {
            var client = oidc.authenticateClient(grant.clientId(), grant.clientSecret());
            var scopes = oidc.scopes(client, grant.scope());
            if (grant.scope() != null && !java.util.Set.of("password", "refresh_token").contains(grant.getGrantType().getValue())) { throw com.agilityhub.core.identity.application.OidcService.invalid("invalid_scope"); }
            if (client == null || !client.getAuthorizationGrantTypes().contains(grant.getGrantType())) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            var issued = switch (grant.getGrantType().getValue()) {
                case "password" -> tokens.password(grant.username(), (String) grant.getCredentials(), grant.clientId(), grant.userAgent(), scopes);
                case "authorization_code" -> oidc.exchange((String) grant.getCredentials(), grant.clientId(), grant.redirectUri(), grant.verifier(), grant.userAgent());
                case "refresh_token" -> oidc.refresh((String) grant.getCredentials(), grant.clientId(), grant.scope() == null ? null : scopes);
                case com.agilityhub.core.identity.application.HandoffService.GRANT -> handoffs.exchange((String) grant.getCredentials(), grant.clientId(), grant.userAgent());
                default -> tokens.magicLink((String) grant.getCredentials(), grant.clientId(), grant.userAgent());
            };
            var jwt = issued.access();
            var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), issued.scopes());
            return new OAuth2AccessTokenAuthenticationToken(client, (Authentication) grant.getPrincipal(), access, issued.oidc() && !issued.scopes().contains("offline_access") ? null : issued.refresh(),
                    issued.oidc() ? java.util.Map.of("id_token", oidc.idToken(issued)) : java.util.Map.of());
        } catch (ApiException failure) {
            throw new OAuth2AuthenticationException(new OAuth2Error(failure.code().name()), failure);
        } finally { grant.eraseCredentials(); }
    }
    @Override public boolean supports(Class<?> type) { return PasswordGrantAuthenticationToken.class.isAssignableFrom(type); }
}
