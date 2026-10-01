package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 receipt (`REBUT`), provider neutral and immutable once issued (R-12-10): only the state fields change, through §5's
 * transitions; corrections are new `MANUAL` invoices or a cancellation. `displayNumber = "{series}-{number:04d}"` and
 * `{clubId, series, number}` is unique (R-12-08). `issueDate` (club-local `YYYY-MM-DD`) and `period` (`YYYY-MM`) are ISO
 * strings, like the other club-local dates of the model. `paymentMethod` is frozen at issue: masked, never a full IBAN.
 * `runId` is null for `MANUAL` and `MIGRATED` invoices. Nothing is ever deleted.
 */
@Document("invoices")
public record Invoice(@Id String id, String clubId, String series, long number, String displayNumber, String issueDate, String period,
        String memberId, MemberSnapshot memberSnapshot, List<Line> lines, Money base, Money tax, Money total,
        PaymentMethodSnapshot paymentMethod, @AuditField InvoiceStatus status, InvoiceKind kind, String runId, String remittanceId,
        boolean includeInNextRun, String note, @AuditField Instant paidAt, @AuditField Instant failedAt, @AuditField String failureReason,
        @AuditField Instant cancelledAt, @AuditField String cancelReason, Money refundedTotal, Map<String, Object> sourceIds,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {
    /** The member as the accounting export needs them at issue (`number`, `fullName`, the holder's `taxId`). */
    public record MemberSnapshot(Integer number, String fullName, String taxId) { }
    /** S12 §3 `InvoiceLine`: description frozen in the club's `defaultLocale`; `tax = round_half_even(base × taxPercent / 100)`. */
    public record Line(int lineNo, InvoiceLineOrigin origin, String priceId, String bookingId, String description, Money base,
            BigDecimal taxPercent, Money tax, Money total) { }
    /** The method frozen at issue: `maskedAccount` (never the IBAN), `holderName`, `mandateRef`, a card's `last4`, a manual `channel`. */
    public record PaymentMethodSnapshot(PaymentMethodType type, String maskedAccount, String holderName, String mandateRef, String last4,
            ManualChannel channel) { }
}
