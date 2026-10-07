package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S12 §3/§6 response forms (E8-T01, WP-12-A). Required by default; a nullable field is not required and is sent as `null`;
 * a provider the club does not use is absent from the `byProvider` maps. Money is `Money {amountMinor, currency}`, months are
 * `YYYY-MM`, business dates club-local `date`, instants UTC. No response carries a full IBAN, the creditor's IBAN, a card
 * credential or a provider secret (R-12-12, T-12-11).
 */
public final class BillingContracts {
    private BillingContracts() { }
    static final String MONTH = "\\d{4}-(0[1-9]|1[0-2])";

    // ---- Invoices (S12 §3 `Invoice`, `InvoiceLine`, `Collection`)
    @Schema(description = "The member at issue, for the accounting export (`taxId` of the holder)")
    public record MemberSnapshot(@Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer number, String fullName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String taxId) { }
    @Schema(description = "S12 §3 InvoiceLine: description frozen in the club's defaultLocale; tax = round_half_even(base × taxPercent / 100), total = base + tax")
    public record InvoiceLine(int lineNo, InvoiceLineOrigin origin, @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String priceId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String bookingId, String description, Money base, BigDecimal taxPercent,
            Money tax, Money total) { }
    @Schema(description = "The payment method frozen at issue: masked account, never the IBAN; a card's last4; a manual channel")
    public record InvoicePaymentMethod(PaymentMethodType type, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String maskedAccount,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String holderName, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String mandateRef,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String last4, @Schema(requiredMode = NOT_REQUIRED, nullable = true) ManualChannel channel) { }
    public record CollectionRefund(Money amount, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String providerRef, Instant at, String reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String byAccountId) { }
    @Schema(description = "S12 §3 Collection: one attempt to collect an invoice, append-only; providerRef = Stripe paymentIntentId, SEPA mandateRef+endToEndId or the manual channel")
    public record Collection(@Schema(format = "uuid") String id, @Schema(format = "uuid") String invoiceId, CollectionProvider provider, Money amount,
            CollectionStatus status, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String providerRef,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String remittanceId, @Schema(minimum = "1") int attempt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String failureCode, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String failureMessage,
            List<CollectionRefund> refunds, Instant createdAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant resolvedAt) { }
    @Schema(description = "S12 §3 Invoice (receipt), immutable once issued (R-12-10): only its state fields change. GET /invoices/{id} adds its collections[] "
            + "(oldest first); refundedTotal = total reads «reemborsat» (PAID, no state of its own). runId is null for MANUAL and MIGRATED invoices.")
    public record Invoice(@Schema(format = "uuid") String id, String series, @Schema(minimum = "1") long number,
            @Schema(description = "{series}-{number:04d}, e.g. 2026-0912") String displayNumber, @Schema(description = "Club-local issue date") LocalDate issueDate,
            @Schema(pattern = MONTH, description = "Billed month") String period, @Schema(format = "uuid") String memberId, MemberSnapshot memberSnapshot,
            List<InvoiceLine> lines, Money base, Money tax, Money total, InvoicePaymentMethod paymentMethod, InvoiceStatus status, InvoiceKind kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String runId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String remittanceId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant paidAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant failedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String failureReason, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant cancelledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "The admin's reason (free text), or ROLLBACK when a rollback cancelled it; "
                    + "rolledBack, not this text, says whether it is rolled back") String cancelReason,
            @Schema(description = "Refunded so far (zero when none)") Money refundedTotal,
            @Schema(description = "SEPA_DD manual invoice to be collected by the next run (R-12-19)") boolean includeInNextRun,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String note, List<Collection> collections, long version, Instant createdAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String createdByAccountId,
            @Schema(description = "Its run was rolled back (R-12-14): every receipt of the run, one the admin had cancelled before included; its "
                    + "number was given back and reissued") boolean rolledBack) { }
    @Schema(description = "A member of the D6 list: id, full name, number")
    public record InvoiceMember(@Schema(format = "uuid") String id, String fullName, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer memberNumber) { }
    @Schema(description = "A row of D6's universal list (CONVENCIONS_API §4): only the row id is required, fields= leaves out the rest. concept = the "
            + "first line's frozen description (with the line count when there are more). The «Estat» label derives from status, paymentMethodType "
            + "and refundedTotal («remesat» = COLLECTING SEPA_DD, «impagat (manual)» = FAILED SEPA_DD, «impagat (targeta)» = FAILED CARD, «reemborsat»).")
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record InvoiceListItem(@Schema(format = "uuid") String id, @Schema(requiredMode = NOT_REQUIRED) String displayNumber,
            @Schema(requiredMode = NOT_REQUIRED) Long number, @Schema(requiredMode = NOT_REQUIRED) LocalDate issueDate,
            @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) String period, @Schema(requiredMode = NOT_REQUIRED) InvoiceMember member,
            @Schema(requiredMode = NOT_REQUIRED) String concept, @Schema(requiredMode = NOT_REQUIRED) Money total,
            @Schema(requiredMode = NOT_REQUIRED) PaymentMethodType paymentMethodType, @Schema(requiredMode = NOT_REQUIRED) InvoiceStatus status,
            @Schema(requiredMode = NOT_REQUIRED) InvoiceKind kind, @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String runId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String remittanceId,
            @Schema(requiredMode = NOT_REQUIRED) Money refundedTotal, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant paidAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant failedAt,
            @Schema(requiredMode = NOT_REQUIRED, description = "Its run was rolled back (R-12-14), whatever its cancelReason: its number was reissued; "
                    + "listed only under the CANCELLED filter") Boolean rolledBack) { }
    public record InvoicePage(List<InvoiceListItem> items, @Schema(minimum = "0") int page, @Schema(minimum = "1") int size,
            @Schema(minimum = "0") long totalItems, @Schema(minimum = "0") int totalPages, List<Filter> appliedFilters) { }
    @Schema(description = "POST /invoices/payments: how many invoices were marked paid")
    public record BulkPaymentResult(@Schema(minimum = "0") int paid, List<Invoice> invoices) { }

    // ---- The member's view (R-12-27): read only, no IBAN
    public record MeInvoiceLine(InvoiceLineOrigin origin, String description, Money total) { }
    @Schema(description = "R-12-27: an invoice of the caller, or of the family group holder the caller belongs to (familyGroup = true). Lines keep their "
            + "frozen description; the client formats period in the reader's locale. No IBAN: maskedAccount only.")
    public record MeInvoice(@Schema(format = "uuid") String id, String displayNumber, LocalDate issueDate, @Schema(pattern = MONTH) String period,
            List<MeInvoiceLine> lines, Money total, InvoiceStatus status, InvoicePaymentMethod paymentMethod,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant paidAt, Money refundedTotal,
            @Schema(description = "The holder's invoice that covers the caller") boolean familyGroup) { }
    public record MeInvoicePage(List<MeInvoice> items, @Schema(minimum = "0") int page, @Schema(minimum = "1") int size,
            @Schema(minimum = "0") long totalItems, @Schema(minimum = "0") int totalPages) { }

    // ---- Remittances (S12 §3 `Remittance`)
    @Schema(description = "The creditor snapshot of the remittance (CLUB.paymentProviders.SEPA_XML): the IBAN only masked")
    public record Creditor(String name, @Schema(description = "SEPA creditor identifier") String id, String maskedIban,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String bic) { }
    @Schema(description = "One PmtInf per sequence type: FRST only with billing.sepa.useFrst, otherwise every transaction is RCUR")
    public record SequenceBreakdown(@JsonProperty("FRST") @Schema(name = "FRST", minimum = "0") int frst,
            @JsonProperty("RCUR") @Schema(name = "RCUR", minimum = "0") int rcur) { }
    @Schema(description = "S12 §3 Remittance (pain.008, R-12-12). messageId = GrpHdr.MsgId (≤ 35 characters). fileAvailable: the XML can be downloaded "
            + "(GET /remittances/{id}/file); the storage key is never published. xsdValidationSkipped is E8-T03's route (c), null otherwise.")
    public record Remittance(@Schema(format = "uuid") String id, @Schema(format = "uuid") String runId, @Schema(pattern = MONTH) String period,
            String messageId, Instant creationAt, LocalDate requestedCollectionDate, Creditor creditor, List<String> collectionIds, @Schema(minimum = "0") int count,
            Money total, SequenceBreakdown sequenceBreakdown, boolean fileAvailable, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant xsdValidatedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean xsdValidationSkipped, RemittanceStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant submittedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String submittedByAccountId) { }
    @Schema(description = "A row of the remittances list (CONVENCIONS_API §4): only the row id is required")
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record RemittanceListItem(@Schema(format = "uuid") String id, @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) String period,
            @Schema(requiredMode = NOT_REQUIRED) String messageId, @Schema(requiredMode = NOT_REQUIRED) Instant creationAt,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate requestedCollectionDate, @Schema(requiredMode = NOT_REQUIRED) Integer count,
            @Schema(requiredMode = NOT_REQUIRED) Money total, @Schema(requiredMode = NOT_REQUIRED) RemittanceStatus status,
            @Schema(requiredMode = NOT_REQUIRED) Boolean fileAvailable, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant submittedAt) { }
    public record RemittancePage(List<RemittanceListItem> items, @Schema(minimum = "0") int page, @Schema(minimum = "1") int size,
            @Schema(minimum = "0") long totalItems, @Schema(minimum = "0") int totalPages, List<Filter> appliedFilters) { }
    @Schema(description = "A short-lived signed URL of the pain.008 XML; its download answers Content-Disposition attachment with fileName")
    public record RemittanceFile(@Schema(format = "uri") String downloadUrl, String fileName, Instant expiresAt) { }

    // ---- Runs and simulations (S12 §3 `BillingRun`, `BillingSimulation`, §6 JSON)
    @Schema(description = "A provider's share of a month: remittanceId only for SEPA_XML, charged/failed only for STRIPE")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProviderTotals(@Schema(minimum = "0") int count, Money total,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid", description = "SEPA_XML only") String remittanceId,
            @Schema(requiredMode = NOT_REQUIRED, minimum = "0", description = "STRIPE only") Integer charged,
            @Schema(requiredMode = NOT_REQUIRED, minimum = "0", description = "STRIPE only") Integer failed) { }
    @Schema(description = "Totals per provider; a provider the club does not use is absent")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ByProvider(@JsonProperty("SEPA_XML") @Schema(name = "SEPA_XML", requiredMode = NOT_REQUIRED) ProviderTotals sepaXml,
            @JsonProperty("STRIPE") @Schema(name = "STRIPE", requiredMode = NOT_REQUIRED) ProviderTotals stripe,
            @JsonProperty("MANUAL") @Schema(name = "MANUAL", requiredMode = NOT_REQUIRED) ProviderTotals manual) { }
    @Schema(description = "R-12-07: a member-level or waiting-receipt incident; skipped[] only contains members not billed by the run")
    public record BillingIncident(@Schema(format = "uuid") String memberId, String memberName, BillingIncidentCode code,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(requiredMode = NOT_REQUIRED, format = "uuid", description = "Waiting receipts only") String invoiceId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(requiredMode = NOT_REQUIRED, description = "Waiting receipts only") String displayNumber) {
        public BillingIncident(String memberId, String memberName, BillingIncidentCode code) { this(memberId, memberName, code, null, null); }
    }
    @Schema(description = "D6 «Actius amb pagament en efectiu»: plannedLeaveDate = the member's leaveDate (S13), null without one")
    public record CashMember(@Schema(format = "uuid") String memberId, String memberName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate plannedLeaveDate) { }
    public record PreviewLine(InvoiceLineOrigin origin, String description, Money total) { }
    @Schema(description = "A receipt of the confirmation: one the run will issue (no invoiceId or displayNumber: the keys are absent, as in S12 §6's "
            + "JSON), or a waiting manual SEPA_DD receipt with includeInNextRun the run will put into its remittance (R-12-19), with its id and number")
    public record InvoicePreview(@Schema(format = "uuid") String memberId, String memberName, PaymentMethodType paymentMethodType,
            List<PreviewLine> lines, Money total,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(requiredMode = NOT_REQUIRED, format = "uuid", description = "Waiting receipts only") String invoiceId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(requiredMode = NOT_REQUIRED, description = "Waiting receipts only") String displayNumber) { }
    @Schema(description = "D6 KPI «Quota d'inactivitat · {count} · {firstMonth} el 1r mes · {following}/mes»")
    public record InactivityFees(@Schema(minimum = "0") int count, Money firstMonth, Money following) { }
    public record SimulationKpis(@Schema(minimum = "0") int count, Money total, ByProvider byProvider, @Schema(minimum = "0") int cashPending,
            InactivityFees inactivityFees,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Default collection date in the billed month, club-local; null for a simulation stored before E90") LocalDate collectionDate) { }
    @Schema(description = "S12 §6 simulation JSON (R-12-07) plus its id, which POST /billing/runs names; only the last one per month is kept")
    public record BillingSimulation(@Schema(format = "uuid") String id, @Schema(pattern = MONTH) String period, Instant at, List<BillingIncident> incidents,
            List<CashMember> cashMembers, SimulationKpis kpis, List<InvoicePreview> invoicesPreview) { }
    @Schema(description = "S12 §3 BillingRun. rollbackable and rollbackBlockers are computed at read (R-12-14); skipped = the members with an incident")
    public record BillingRun(@Schema(format = "uuid") String id, @Schema(pattern = MONTH) String period, BillingRunStatus status,
            @Schema(format = "uuid") String simulationId, List<String> invoiceIds, ByProvider byProvider,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate collectionDate, Instant startedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant finishedAt, boolean rollbackable, List<RollbackBlocker> rollbackBlockers,
            List<BillingIncident> skipped, @Schema(format = "uuid") String createdByAccountId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant rolledBackAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String rollbackReason) { }
    @Schema(description = "201 of POST /billing/runs: the run, its remittance (null without SEPA_XML) and the skipped members")
    public record BillingRunResult(BillingRun run, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Remittance remittance, List<BillingIncident> skipped) { }
    public record CardChargeSkip(@Schema(format = "uuid") String invoiceId, @Schema(description = "e.g. NO_PAYMENT_METHOD (R-12-13)") String reason) { }
    @Schema(description = "202 of POST /billing/runs/{id}/card-charges (R-12-13): the results arrive by webhook")
    public record CardChargesResult(@Schema(minimum = "0") int submitted, List<CardChargeSkip> skipped) { }
    public record RollbackResult(@Schema(minimum = "0") int cancelledInvoices, @Schema(minimum = "0") int restoredMembers) { }
    @Schema(description = "GET /billing/periods/{period}: the simulation of D6's step 1")
    public record PeriodSimulation(@Schema(format = "uuid") String id, Instant at, SimulationKpis kpis, List<BillingIncident> incidents, List<CashMember> cashMembers) { }
    public record PeriodRun(@Schema(format = "uuid") String id, BillingRunStatus status, ByProvider byProvider, boolean rollbackable,
            List<RollbackBlocker> rollbackBlockers) { }
    public record PeriodRemittance(@Schema(format = "uuid") String id, RemittanceStatus status, boolean fileAvailable) { }
    @Schema(description = "D6's chips: Tots · Pendents · Remesats · Cobrats · Impagats")
    public record InvoiceCounts(@Schema(minimum = "0") long all, @Schema(minimum = "0") long pending, @Schema(minimum = "0") long remitted,
            @Schema(minimum = "0") long paid, @Schema(minimum = "0") long failed) { }
    @Schema(description = "S12 §6 GET /billing/periods/{period}: the month as D6 shows it; simulation, run and remittance are null until they exist")
    public record BillingPeriod(@Schema(pattern = MONTH) String period, @Schema(requiredMode = NOT_REQUIRED, nullable = true) PeriodSimulation simulation,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) PeriodRun run, @Schema(requiredMode = NOT_REQUIRED, nullable = true) PeriodRemittance remittance,
            InvoiceCounts counts) { }

    // ---- Upfront payments, packs, pending charges, checkout, card setup
    @Schema(description = "S12 §3 UpfrontPayment.provider: STRIPE {checkoutSessionId, paymentIntentId, chargeId} · MANUAL {channel, paidAt, reference}")
    public record UpfrontPaymentProvider(UpfrontProvider type, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String checkoutSessionId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String paymentIntentId, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String chargeId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ManualChannel channel, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant paidAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String reference) { }
    @Schema(description = "S12 §3 UpfrontPayment (payment on the spot): entry fee, first month, pack, single class, activity, other. signupConcept keeps "
            + "S04's concept of a signup row (e.g. ADDITIONAL_DOG_FEE); provider is null while nothing was received.")
    public record UpfrontPayment(@Schema(format = "uuid") String id, @Schema(format = "uuid") String memberId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String dogId, UpfrontConcept concept,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String signupConcept, Money amountDue, Money amountPaid, UpfrontStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) UpfrontPaymentProvider provider,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String bookingId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String activityRegistrationId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String packBalanceId, List<CollectionRefund> refunds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String note, Instant createdAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant paidAt) { }
    public record UpfrontPayments(List<UpfrontPayment> items) { }
    @Schema(description = "202 of a refund (R-12-20): the provider confirms it by webhook (charge.refunded)")
    public record RefundAccepted(@Schema(format = "uuid") String id, Money amount, @Schema(description = "The refund's provider reference, null until the provider answers",
            requiredMode = NOT_REQUIRED, nullable = true) String providerRef) { }
    public record PackMovement(@Schema(format = "uuid") String id, PackMovementType type, int delta,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String bookingId, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String byAccountId, Instant at) { }
    @Schema(description = "S12 §3/§6 pack balance (named PackBalanceDetail: PackBalance is S08's booking summary). remaining is derived; "
            + "unused sessions are lost at expiresOn (B11)")
    public record PackBalanceDetail(@Schema(format = "uuid") String id, @Schema(format = "uuid") String memberId, @Schema(format = "uuid") String dogId,
            @Schema(format = "uuid") String planId, String planName, @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String upfrontPaymentId,
            @Schema(minimum = "0") int sessionsTotal, @Schema(minimum = "0") int consumed, int remaining, LocalDate openedOn, LocalDate expiresOn,
            PackBalanceState state, List<PackMovement> movements, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant expiryWarnedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant expiredAt) { }
    @Schema(description = "S12 §3 PendingCharge: a single class charged by consumption (R-12-25), billed by the next run or voided")
    public record PendingCharge(@Schema(format = "uuid") String id, @Schema(format = "uuid") String memberId, @Schema(format = "uuid") String dogId,
            @Schema(format = "uuid") String bookingId, @Schema(format = "uuid") String priceId, Money amount, String description, Instant createdAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String invoiceId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant voidedAt) { }
    @Schema(description = "GET /checkout-sessions/{id}: the return screen shows «Pagament rebut» only with PAID (polling ≤ 10 s)")
    public record CheckoutSessionView(String checkoutSessionId, CheckoutStatus status) { }
    public record CardSetupLink(@Schema(format = "uri") String checkoutUrl) { }

    // ---- Error details (CATALEG_ERRORS §3 rule 2)
    public record RunNotRollbackableDetails(List<RollbackBlocker> reasons) { }
    public record CollectionDateTooSoonDetails(LocalDate requested, LocalDate earliest) { }
    public record MaxAttemptsDetails(@Schema(minimum = "1") int attempts, @Schema(minimum = "1") int max) { }
}
