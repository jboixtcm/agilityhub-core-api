package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("impersonation_grants")
public record ImpersonationGrant(@Id String id, String clubId, String actorAccountId,
        String impersonatedMemberId, String impersonatedAccountId, String reason,
        Instant expiresAt, Instant revokedAt) implements TenantEntity { }
