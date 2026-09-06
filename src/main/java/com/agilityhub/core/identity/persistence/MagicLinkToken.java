package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("magic_link_tokens")
public record MagicLinkToken(@Id String id, @JsonIgnore @Sensitive(Sensitive.Strategy.HIDE) String tokenHash, String accountId,
                             String clubId, String clientId, Purpose purpose, String redirectUri, Instant createdAt,
                             Instant expiresAt, Instant usedAt, String ipHash, String userAgent) implements TenantEntity {
    public enum Purpose { LOGIN, RESET, WELCOME, ACCESS_RESEND, RECOGNITION }
    @Override public String toString() { return "MagicLinkToken[redacted]"; }
}
