package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 SEPA remittance (pain.008, R-12-12). `messageId` is the file's `GrpHdr.MsgId` (≤ 35 characters, unique per club);
 * `requestedCollectionDate` is club-local `YYYY-MM-DD`. `creditor` is a snapshot of `CLUB.paymentProviders.SEPA_XML` whose
 * `iban` never leaves the database (every response masks it). `xsdValidationSkipped` is E8-T03's route (c), null otherwise.
 * The file is kept after a rollback.
 */
@Document("remittances")
public record Remittance(@Id String id, String clubId, String runId, String period, String messageId, Instant creationAt,
        String requestedCollectionDate, Creditor creditor, List<String> collectionIds, int count, Money total, SequenceBreakdown sequenceBreakdown,
        String fileKey, Instant xsdValidatedAt, Boolean xsdValidationSkipped, @AuditField RemittanceStatus status, Instant submittedAt,
        String submittedByAccountId, @Version Long version, Instant createdAt, String createdByAccountId) implements TenantEntity {
    public record Creditor(String name, String id, @Sensitive String iban, String bic) { }
    /** One `PmtInf` per sequence type: `FRST` only with `billing.sepa.useFrst`, otherwise every transaction is `RCUR`. */
    public record SequenceBreakdown(int frst, int rcur) { }
}
