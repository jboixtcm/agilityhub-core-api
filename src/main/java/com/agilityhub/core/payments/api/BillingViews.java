package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingQueries;
import com.agilityhub.core.payments.application.BillingRunService;
import com.agilityhub.core.payments.domain.BillingRunStatus;
import com.agilityhub.core.payments.domain.RollbackBlocker;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.BillingSimulation;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.payments.persistence.Remittance;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static com.agilityhub.core.payments.api.BillingContracts.*;

/**
 * The S12 §3/§6 published forms of the stored billing documents (E8-T02). Nothing here publishes a full IBAN (the invoice's
 * method is frozen masked; the remittance's creditor IBAN is masked here), a storage key or a provider secret (T-12-11).
 */
final class BillingViews {
    private BillingViews() { }

    static BillingContracts.Invoice invoice(Invoice invoice, List<Collection> collections) {
        var method = invoice.paymentMethod();
        return new BillingContracts.Invoice(invoice.id(), invoice.series(), invoice.number(), invoice.displayNumber(), LocalDate.parse(invoice.issueDate()),
                invoice.period(), invoice.memberId(), new MemberSnapshot(invoice.memberSnapshot().number(), invoice.memberSnapshot().fullName(),
                        invoice.memberSnapshot().taxId()),
                invoice.lines().stream().map(line -> new InvoiceLine(line.lineNo(), line.origin(), line.priceId(), line.bookingId(), line.description(), line.base(),
                        line.taxPercent(), line.tax(), line.total())).toList(), invoice.base(), invoice.tax(), invoice.total(), method(method),
                invoice.status(), invoice.kind(), invoice.runId(), invoice.remittanceId(), invoice.paidAt(), invoice.failedAt(), invoice.failureReason(),
                invoice.cancelledAt(), invoice.cancelReason(), invoice.refundedTotal(), invoice.includeInNextRun(), invoice.note(),
                collections.stream().map(BillingViews::collection).toList(), invoice.version() == null ? 0 : invoice.version(), invoice.createdAt(),
                invoice.createdByAccountId());
    }
    static InvoicePaymentMethod method(Invoice.PaymentMethodSnapshot method) {
        return new InvoicePaymentMethod(method.type(), method.maskedAccount(), method.holderName(), method.mandateRef(), method.last4(), method.channel());
    }
    static BillingContracts.Collection collection(Collection collection) {
        return new BillingContracts.Collection(collection.id(), collection.invoiceId(), collection.provider(), collection.amount(), collection.status(),
                collection.publishedReference(), collection.remittanceId(), collection.attempt(), collection.failureCode(), collection.failureMessage(),
                collection.refunds() == null ? List.of() : collection.refunds().stream().map(refund -> new CollectionRefund(refund.amount(), refund.providerRef(),
                        refund.at(), refund.reason(), refund.byAccountId())).toList(), collection.createdAt(), collection.resolvedAt());
    }

    static BillingContracts.BillingSimulation simulation(BillingSimulation simulation) {
        return new BillingContracts.BillingSimulation(simulation.id(), simulation.period(), simulation.at(), incidents(simulation.incidents()),
                cash(simulation), kpis(simulation.kpis()),
                simulation.invoicesPreview().stream().map(preview -> new InvoicePreview(preview.memberId(), preview.memberName(), preview.paymentMethodType(),
                        preview.lines().stream().map(line -> new PreviewLine(line.origin(), line.description(), line.total())).toList(), preview.total())).toList());
    }
    static List<BillingIncident> incidents(List<BillingSimulation.Incident> incidents) {
        return incidents.stream().map(incident -> new BillingIncident(incident.memberId(), incident.memberName(), incident.code())).toList();
    }
    static List<CashMember> cash(BillingSimulation simulation) {
        return simulation.cashMembers().stream().map(cash -> new CashMember(cash.memberId(), cash.memberName(),
                cash.plannedLeaveDate() == null ? null : LocalDate.parse(cash.plannedLeaveDate()))).toList();
    }
    static SimulationKpis kpis(BillingSimulation.Kpis kpis) {
        var by = kpis.byProvider();
        return new SimulationKpis(kpis.count(), kpis.total(), new ByProvider(totals(by.sepaXml()), totals(by.stripe()), totals(by.manual())), kpis.cashPending(),
                new InactivityFees(kpis.inactivityFees().count(), kpis.inactivityFees().firstMonth(), kpis.inactivityFees().following()));
    }
    private static ProviderTotals totals(BillingSimulation.Totals totals) {
        return totals == null ? null : new ProviderTotals(totals.count(), totals.total(), null, null, null);
    }

    static BillingContracts.BillingRun run(BillingRunService.RunOutcome outcome) {
        var run = outcome.run();
        boolean live = run.status() != BillingRunStatus.ROLLED_BACK;
        List<RollbackBlocker> blockers = live ? outcome.blockers() : List.of();
        return new BillingContracts.BillingRun(run.id(), run.period(), run.status(), run.simulationId(), run.invoiceIds(), byProvider(run.byProvider()),
                run.collectionDate() == null ? null : LocalDate.parse(run.collectionDate()), run.startedAt(), run.finishedAt(), live && blockers.isEmpty(), blockers,
                run.skipped().stream().map(skip -> new BillingIncident(skip.memberId(), skip.memberName(), skip.code())).toList(), run.createdByAccountId(),
                run.rolledBackAt(), run.rollbackReason());
    }
    static ByProvider byProvider(BillingRun.ByProvider by) {
        return by == null ? new ByProvider(null, null, null) : new ByProvider(totals(by.sepaXml()), totals(by.stripe()), totals(by.manual()));
    }
    private static ProviderTotals totals(BillingRun.Totals totals) {
        return totals == null ? null : new ProviderTotals(totals.count(), totals.total(), totals.remittanceId(), totals.charged(), totals.failed());
    }
    static BillingRunResult runResult(BillingRunService.RunOutcome outcome) {
        var run = run(outcome);
        return new BillingRunResult(run, outcome.remittance() == null ? null : remittance(outcome.remittance()), run.skipped());
    }

    static BillingContracts.Remittance remittance(Remittance remittance) {
        var creditor = remittance.creditor();
        var breakdown = remittance.sequenceBreakdown();
        return new BillingContracts.Remittance(remittance.id(), remittance.runId(), remittance.period(), remittance.messageId(), remittance.creationAt(),
                LocalDate.parse(remittance.requestedCollectionDate()),
                new Creditor(creditor == null || creditor.name() == null ? "" : creditor.name(), creditor == null || creditor.id() == null ? "" : creditor.id(),
                        creditor == null ? "" : masked(creditor.iban()), creditor == null ? null : creditor.bic()),
                remittance.collectionIds(), remittance.count(), remittance.total(), new SequenceBreakdown(breakdown == null ? 0 : breakdown.frst(),
                        breakdown == null ? remittance.count() : breakdown.rcur()), remittance.fileKey() != null, remittance.xsdValidatedAt(),
                remittance.xsdValidationSkipped(), remittance.status(), remittance.submittedAt(), remittance.submittedByAccountId());
    }
    /** «···· ···· ···· ···· 4955»: the creditor's account as every response shows it (R-12-12, T-12-11). */
    static String masked(String iban) {
        if (iban == null || iban.isBlank()) { return ""; }
        String compact = iban.replaceAll("\\s", "");
        return "···· ···· ···· ···· " + compact.substring(Math.max(0, compact.length() - 4));
    }

    static BillingPeriod period(BillingQueries.Period period) {
        var simulation = period.simulation();
        var outcome = period.run();
        Map<String, Long> counts = period.counts();
        long all = counts.values().stream().mapToLong(Long::longValue).sum();
        PeriodRun run = null; PeriodRemittance remittance = null;
        if (outcome != null) {
            var view = run(outcome);
            run = new PeriodRun(view.id(), view.status(), view.byProvider(), view.rollbackable(), view.rollbackBlockers());
            if (outcome.remittance() != null) {
                remittance = new PeriodRemittance(outcome.remittance().id(), outcome.remittance().status(), outcome.remittance().fileKey() != null);
            }
        }
        return new BillingPeriod(period.period().toString(), simulation == null ? null : new PeriodSimulation(simulation.id(), simulation.at(),
                kpis(simulation.kpis()), incidents(simulation.incidents()), cash(simulation)), run, remittance,
                new InvoiceCounts(all, counts.getOrDefault("PENDING", 0L), counts.getOrDefault("COLLECTING", 0L), counts.getOrDefault("PAID", 0L),
                        counts.getOrDefault("FAILED", 0L)));
    }

    static MeInvoice meInvoice(BillingQueries.MemberInvoice item) {
        var invoice = item.invoice();
        return new MeInvoice(invoice.id(), invoice.displayNumber(), LocalDate.parse(invoice.issueDate()), invoice.period(),
                invoice.lines().stream().map(line -> new MeInvoiceLine(line.origin(), line.description(), line.total())).toList(), invoice.total(), invoice.status(),
                method(invoice.paymentMethod()), invoice.paidAt(), invoice.refundedTotal(), item.familyGroup());
    }
    static BillingContracts.PendingCharge pendingCharge(PendingCharge charge) {
        return new BillingContracts.PendingCharge(charge.id(), charge.memberId(), charge.dogId(), charge.bookingId(), charge.priceId(), charge.amount(),
                charge.description(), charge.createdAt(), charge.invoiceId(), charge.voidedAt());
    }
}
