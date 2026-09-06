package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("refresh_tokens")
public record RefreshToken(@Id String id, String tokenHash, String accountId, String clubId,
                           String clientId, String familyId, long tokenFamilyVersion,
                           Instant createdAt, Instant expiresAt, Instant lastUsedAt,
                           Instant revokedAt, String replacedByHash) implements TenantEntity { }
