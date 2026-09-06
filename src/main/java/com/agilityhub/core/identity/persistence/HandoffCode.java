package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("handoff_codes")
public record HandoffCode(@Id String id, String clubId, String accountId, String targetClientId,
        String codeHash, String sourceClientId, String sourceFamilyId, long tokenFamilyVersion,
        Instant expiresAt, Instant usedAt) implements TenantEntity {
    @Override public String toString() { return "HandoffCode[redacted]"; }
}
