package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 collection attempt (`COBRAMENT`), append-only (R-12-10): a retry is a new row with `attempt + 1`, a rollback a new
 * `FAILED{ROLLBACK}` row. `providerRef` is Stripe's `paymentIntentId`, SEPA's `mandateRef`+`endToEndId` or the manual
 * `channel`; `{clubId, providerRef}` is unique when present.
 */
@Document("collections")
public record Collection(@Id String id, String clubId, String invoiceId, CollectionProvider provider, Money amount, CollectionStatus status,
        String providerRef, String remittanceId, int attempt, String failureCode, String failureMessage, List<Refund> refunds,
        Instant createdAt, Instant resolvedAt) implements TenantEntity {
    public record Refund(Money amount, String providerRef, Instant at, String reason, String byAccountId) { }
}
