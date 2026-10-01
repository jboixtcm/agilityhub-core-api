package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 R-12-11 per-club billing lock: `_id = "{clubId}:billing"`, held by one simulation, run or rollback at a time
 * (`409 BILLING_BUSY` otherwise). The TTL index on `expiresAt` removes a lock its holder never released.
 */
@Document("billing_locks")
public record BillingLock(@Id String id, String clubId, String holder, Instant acquiredAt, Instant expiresAt) implements TenantEntity {
    public static String idFor(String clubId) { return clubId + ":billing"; }
}
