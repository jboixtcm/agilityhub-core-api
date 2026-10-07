package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.ManualChannel;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 collection attempt (`COBRAMENT`), append-only (R-12-10): a retry is a new row with `attempt + 1`, a rollback a new
 * `FAILED{ROLLBACK}` row. The stored `providerRef` is the provider's own unique reference — Stripe's `paymentIntentId` — and
 * `{clubId, providerRef}` is unique when present. A SEPA attempt keeps its `mandateRef` and `endToEndId` (the invoice's
 * `displayNumber`, which a rollback and a new generation reproduce) and a manual payment its `channel` and `reference`
 * (E8-T02): neither is unique, so they stay out of `providerRef`, and the published `providerRef` is derived from them
 * (S12 §3: «SEPA `mandateRef`+`endToEndId` · manual `channel`»).
 */
@Document("collections")
public record Collection(@Id String id, String clubId, String invoiceId, CollectionProvider provider, Money amount, CollectionStatus status,
        String providerRef, String remittanceId, int attempt, String failureCode, String failureMessage, List<Refund> refunds,
        Instant createdAt, Instant resolvedAt, String mandateRef, String endToEndId, ManualChannel channel, String reference, Instant mandateSignedAt) implements TenantEntity {
    /** Rows written before E90 have no collection signature; the writer uses the current mandate's signature. */
    public Collection(String id, String clubId, String invoiceId, CollectionProvider provider, Money amount, CollectionStatus status,
            String providerRef, String remittanceId, int attempt, String failureCode, String failureMessage, List<Refund> refunds,
            Instant createdAt, Instant resolvedAt, String mandateRef, String endToEndId, ManualChannel channel, String reference) {
        this(id, clubId, invoiceId, provider, amount, status, providerRef, remittanceId, attempt, failureCode, failureMessage, refunds,
                createdAt, resolvedAt, mandateRef, endToEndId, channel, reference, null);
    }
    public record Refund(Money amount, String providerRef, Instant at, String reason, String byAccountId) { }
    /** The attempt as E8-T01 declared it (no SEPA or manual reference). */
    public Collection(String id, String clubId, String invoiceId, CollectionProvider provider, Money amount, CollectionStatus status,
            String providerRef, String remittanceId, int attempt, String failureCode, String failureMessage, List<Refund> refunds,
            Instant createdAt, Instant resolvedAt) {
        this(id, clubId, invoiceId, provider, amount, status, providerRef, remittanceId, attempt, failureCode, failureMessage, refunds, createdAt,
                resolvedAt, null, null, null, null);
    }
    /** S12 §3's `providerRef` as published: Stripe's id, `{mandateRef}/{endToEndId}` for SEPA, the channel (and reference) for MANUAL. */
    public String publishedReference() {
        if (providerRef != null) { return providerRef; }
        if (provider == CollectionProvider.SEPA_XML && mandateRef != null) { return endToEndId == null ? mandateRef : mandateRef + "/" + endToEndId; }
        if (provider == CollectionProvider.MANUAL && channel != null) { return reference == null || reference.isBlank() ? channel.name() : channel.name() + " · " + reference; }
        return null;
    }
}
