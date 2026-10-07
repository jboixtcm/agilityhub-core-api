package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.BillingIncidentCode;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 simulation of a month (R-12-07): only the last one per club and month is kept (unique `{clubId, period}`; a new
 * simulation replaces it). A run requires one made after the last relevant change (`409 SIMULATION_STALE`).
 * `cashMembers[].plannedLeaveDate` is the member's `leaveDate` (S13), club-local `YYYY-MM-DD`.
 * E8-T07 (R-12-07, R-12-19): a preview row with an `invoiceId` is a waiting manual receipt the run will remit; `chargeIds` and
 * `waitingInvoiceIds` are the unbilled `PendingCharge`s and the waiting `includeInNextRun` receipts the drafts bill (never
 * published): the run is stale when either set changed since. A simulation stored before has neither (null: an empty set).
 */
@Document("billing_simulations")
public record BillingSimulation(@Id String id, String clubId, String period, Instant at, List<Incident> incidents, List<CashMember> cashMembers,
        List<PreviewInvoice> invoicesPreview, Kpis kpis, String createdByAccountId, List<String> chargeIds, List<String> waitingInvoiceIds) implements TenantEntity {
    /** The simulation as E8-T01 declared it (no charge or receipt sets). */
    public BillingSimulation(String id, String clubId, String period, Instant at, List<Incident> incidents, List<CashMember> cashMembers,
            List<PreviewInvoice> invoicesPreview, Kpis kpis, String createdByAccountId) {
        this(id, clubId, period, at, incidents, cashMembers, invoicesPreview, kpis, createdByAccountId, null, null);
    }
    public record Incident(String memberId, String memberName, BillingIncidentCode code, String invoiceId, String displayNumber) {
        public Incident(String memberId, String memberName, BillingIncidentCode code) { this(memberId, memberName, code, null, null); }
    }
    public record CashMember(String memberId, String memberName, String plannedLeaveDate) { }
    /** `invoiceId` and `displayNumber`: a waiting receipt the run remits (R-12-19); null for an invoice the run will issue. */
    public record PreviewInvoice(String memberId, String memberName, PaymentMethodType paymentMethodType, List<PreviewLine> lines, Money total,
            String invoiceId, String displayNumber) {
        public PreviewInvoice(String memberId, String memberName, PaymentMethodType paymentMethodType, List<PreviewLine> lines, Money total) {
            this(memberId, memberName, paymentMethodType, lines, total, null, null);
        }
    }
    public record PreviewLine(InvoiceLineOrigin origin, String description, Money total) { }
    public record Kpis(int count, Money total, ByProvider byProvider, int cashPending, InactivityFees inactivityFees, String collectionDate) {
        public Kpis(int count, Money total, ByProvider byProvider, int cashPending, InactivityFees inactivityFees) {
            this(count, total, byProvider, cashPending, inactivityFees, null);
        }
    }
    /** A provider the club does not use stays null. */
    public record ByProvider(Totals sepaXml, Totals stripe, Totals manual) { }
    public record Totals(int count, Money total) { }
    public record InactivityFees(int count, Money firstMonth, Money following) { }
}
