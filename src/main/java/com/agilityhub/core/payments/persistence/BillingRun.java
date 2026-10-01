package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.BillingIncidentCode;
import com.agilityhub.core.payments.domain.BillingRunStatus;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 billing run (one generation of a month, R-12-11). One **live** run per club and period: the partial unique index
 * `{clubId, period}` on `status ∈ {GENERATED, CHARGING, COMPLETED}` makes a second one fail, while a `ROLLED_BACK` run
 * leaves it. `previousDates` keeps each included member's `nextInvoiceDate` before the run, so the rollback restores it
 * (R-12-14); `rollbackable` is computed by the reader, never stored. `collectionDate` is club-local `YYYY-MM-DD`.
 */
@Document("billing_runs")
public record BillingRun(@Id String id, String clubId, String period, @AuditField BillingRunStatus status, String simulationId, List<String> invoiceIds,
        ByProvider byProvider, String collectionDate, Instant startedAt, Instant finishedAt, List<Skipped> skipped, List<PreviousDate> previousDates,
        long firstNumber, String createdByAccountId, @AuditField Instant rolledBackAt, @AuditField String rollbackReason,
        @Version Long version, Instant createdAt) implements TenantEntity {
    /** A provider the club does not use stays null. */
    public record ByProvider(Totals sepaXml, Totals stripe, Totals manual) { }
    /** `remittanceId` only for `SEPA_XML`; `charged`/`failed` only for `STRIPE`. */
    public record Totals(int count, Money total, String remittanceId, Integer charged, Integer failed) { }
    public record Skipped(String memberId, String memberName, BillingIncidentCode code) { }
    public record PreviousDate(String memberId, String nextInvoiceDate) { }
}
