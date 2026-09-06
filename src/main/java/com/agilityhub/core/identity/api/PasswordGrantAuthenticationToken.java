package com.agilityhub.core.identity.api;

import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

public final class PasswordGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {
    private final String clientId;
    private final String username;
    private String secret;
    public PasswordGrantAuthenticationToken(String grant, String clientId, String username, String secret) {
        super(new AuthorizationGrantType(grant), new AnonymousAuthenticationToken("public-clubs-client", clientId,
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")), Map.of());
        this.clientId = clientId; this.username = username; this.secret = secret;
    }
    public String clientId() { return clientId; }
    public String username() { return username; }
    @Override public Object getCredentials() { return secret; }
    @Override public void eraseCredentials() { super.eraseCredentials(); secret = null; }
}
