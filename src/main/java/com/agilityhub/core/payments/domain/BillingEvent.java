package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S12 §7 events (CATALEG_ESDEVENIMENTS «Facturació i pagaments» and Annex A), published through the outbox in the writing
 * transaction (E8-T02…T06 publish them; E8-T01 declares the contract). Payloads as S12 §7 writes them:
 * `InvoiceIssued{invoiceId, memberId, period, total, paymentMethodType, kind}` · `InvoiceCollecting{invoiceId, provider,
 * collectionId}` · `InvoicePaid{invoiceId, provider, paidAt}` · `InvoiceFailed{invoiceId, provider, reason}` ·
 * `InvoiceCancelled{invoiceId, reason}` · `RemittanceSimulated{simulationId, period, incidents, totals}` ·
 * `RemittanceGenerated{remittanceId, runId, invoiceIds, fileKey}` · `RemittanceRolledBack{remittanceId, runId, invoiceIds}` ·
 * `UpfrontPayment*{paymentId, …}` · `Pack*{packBalanceId, memberId, dogId, …}` · `StripeWebhookReceived{eventId, type,
 * outcome}` · the S12 §13 proposals `BillingRunCreated`, `BillingRunCompleted`, `PackAdjusted{…, delta, reason}`,
 * `MemberCardInvalidated` · S15's `RemittanceReminderDue{period, pendingMembers}` (P10, N-41). The signup rows of S04 keep
 * {@link SignupPaymentEvent} for `UpfrontPaymentRecorded/Succeeded` (E3-T03).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record BillingEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public BillingEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        InvoiceIssued("Invoice"), InvoiceCollecting("Invoice"), InvoicePaid("Invoice"), InvoiceFailed("Invoice"), InvoiceCancelled("Invoice"),
        RemittanceSimulated("BillingSimulation"), RemittanceGenerated("Remittance"), RemittanceRolledBack("Remittance"),
        UpfrontPaymentRecorded("UpfrontPayment"), UpfrontPaymentSucceeded("UpfrontPayment"), UpfrontPaymentFailed("UpfrontPayment"), UpfrontRefundIntervention("UpfrontPayment"),
        PackOpened("PackBalance"), PackConsumed("PackBalance"), PackRefunded("PackBalance"), PackLowBalance("PackBalance"),
        PackExpiring("PackBalance"), PackExpired("PackBalance"), PackAdjusted("PackBalance"),
        StripeWebhookReceived("StripeEvent"), BillingRunCreated("BillingRun"), BillingRunCompleted("BillingRun"),
        MemberCardInvalidated("Member"), RemittanceReminderDue("Club");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
