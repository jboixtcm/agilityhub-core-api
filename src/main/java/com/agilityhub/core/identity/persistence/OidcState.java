package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

public final class OidcState {
    private OidcState() { }
    public record Request(String clientId, String redirectUri, Set<String> scopes, String state, String challenge,
                          String nonce, String prompt, String loginHint, String uiLocales, Long maxAge, String clubId) { }
    @Document("oidc_flows")
    public record Flow(@Id String id, String browserHash, Request request, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "OidcFlow[redacted]"; }
    }
    @Document("oidc_browser_sessions")
    public record Browser(@Id String id, String accountId, String familyId, Instant authTime, Instant expiresAt) {
        @Override public String toString() { return "OidcBrowser[redacted]"; }
    }
    @Document("oidc_codes")
    public record Code(@Id String id, Request request, String accountId, String browserHash, String familyId,
                       Instant authTime, Instant expiresAt) {
        @Override public String toString() { return "OidcCode[redacted]"; }
    }
}
