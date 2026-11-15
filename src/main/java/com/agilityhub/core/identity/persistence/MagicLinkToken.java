package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A one-time link. `email` and `requestedByAccountId` belong to an `EMAIL_CHANGE` link only (AUTH-02, S03 R-03-06b): the
 * address the account's login e-mail becomes when the link is used, and the club ADMIN who asked for it from D10.
 */
@Document("magic_link_tokens")
public record MagicLinkToken(@Id String id, @JsonIgnore @Sensitive(Sensitive.Strategy.HIDE) String tokenHash, String accountId,
                             String clubId, String clientId, Purpose purpose, String redirectUri, Instant createdAt,
                             Instant expiresAt, Instant usedAt, String ipHash, String userAgent, String email,
                             String requestedByAccountId) implements TenantEntity {
    public MagicLinkToken(String id, String tokenHash, String accountId, String clubId, String clientId, Purpose purpose, String redirectUri,
                          Instant createdAt, Instant expiresAt, Instant usedAt, String ipHash, String userAgent) {
        this(id, tokenHash, accountId, clubId, clientId, purpose, redirectUri, createdAt, expiresAt, usedAt, ipHash, userAgent, null, null);
    }
    public enum Purpose { LOGIN, RESET, WELCOME, ACCESS_RESEND, RECOGNITION, EMAIL_CHANGE }
    @Override public String toString() { return "MagicLinkToken[redacted]"; }
}
