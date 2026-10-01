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
 */
@Document("billing_simulations")
public record BillingSimulation(@Id String id, String clubId, String period, Instant at, List<Incident> incidents, List<CashMember> cashMembers,
        List<PreviewInvoice> invoicesPreview, Kpis kpis, String createdByAccountId) implements TenantEntity {
    public record Incident(String memberId, String memberName, BillingIncidentCode code) { }
    public record CashMember(String memberId, String memberName, String plannedLeaveDate) { }
    public record PreviewInvoice(String memberId, String memberName, PaymentMethodType paymentMethodType, List<PreviewLine> lines, Money total) { }
    public record PreviewLine(InvoiceLineOrigin origin, String description, Money total) { }
    public record Kpis(int count, Money total, ByProvider byProvider, int cashPending, InactivityFees inactivityFees) { }
    /** A provider the club does not use stays null. */
    public record ByProvider(Totals sepaXml, Totals stripe, Totals manual) { }
    public record Totals(int count, Money total) { }
    public record InactivityFees(int count, Money firstMonth, Money following) { }
}
