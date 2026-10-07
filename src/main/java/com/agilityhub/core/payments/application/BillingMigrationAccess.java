package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** S18 load boundary: immutable historical invoices, with no collection, remittance or per-record notification. */
@Service
public class BillingMigrationAccess {
    public record Receipt(String sourceId, long number, String displayNumber, LocalDate date, YearMonth period, String memberId,
            Integer memberNumber, String memberName, String holderTaxId, String concept, Money base, java.math.BigDecimal taxPercent,
            Money tax, Money total, String method, String status) { }
    public record Pack(String sourceId, String memberId, String dogId, String planId, LocalDate openedOn, int consumed, LocalDate cutover) { }
    private final InvoiceRepository invoices; private final PackBalanceRepository packs; private final PackBalanceService balances;
    private final BillingSimulationService simulations; private final Clock clock;
    public BillingMigrationAccess(InvoiceRepository invoices, PackBalanceRepository packs, PackBalanceService balances,
            BillingSimulationService simulations, Clock clock) {
        this.invoices = invoices; this.packs = packs; this.balances = balances; this.simulations = simulations; this.clock = clock;
    }
    public Set<String> receiptSources() { return sources(invoices.findAll().stream().map(Invoice::sourceIds).toList(), "playoffReceiptId"); }
    public Set<String> packSources() { return sources(packs.findAll().stream().map(PackBalance::sourceIds).toList(), "playoffPackId"); }
    private static Set<String> sources(List<Map<String, Object>> rows, String field) {
        var result = new HashSet<String>(); for (var row : rows) { if (row != null && row.get(field) != null) { result.add(row.get(field).toString()); } } return result;
    }
    public void receipt(Receipt receipt) {
        String id = id("invoice", receipt.sourceId()); if (invoices.findById(id).isPresent()) { return; }
        var status = InvoiceStatus.valueOf(receipt.status()); var zero = new Money(0, receipt.total().currency());
        var invoice = new Invoice(id, TenantContext.require(), "PLAYOFF", receipt.number(), receipt.displayNumber(), receipt.date().toString(), receipt.period().toString(),
                receipt.memberId(), new Invoice.MemberSnapshot(receipt.memberNumber(), receipt.memberName(), receipt.holderTaxId()),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MIGRATED, null, null, receipt.concept(), receipt.base(), receipt.taxPercent(), receipt.tax(), receipt.total())),
                receipt.base(), receipt.tax(), receipt.total(), new Invoice.PaymentMethodSnapshot(PaymentMethodType.valueOf(receipt.method()), null, null, null, null, null),
                status, InvoiceKind.MIGRATED, null, null, false, null, null, null, null, null, null, zero,
                Map.of("playoffReceiptId", receipt.sourceId()), null, clock.instant(), null, clock.instant(), null);
        invoices.insert(invoice);
    }
    public void pack(Pack pack) {
        balances.openMigrated(id("pack", pack.sourceId()), pack.sourceId(), pack.memberId(), pack.dogId(), pack.planId(), pack.openedOn(), pack.consumed(), pack.cutover());
    }
    public record Reconciliation(long totalMinor, Map<String, Long> byMember, Map<String, String> incidents) { }
    public Reconciliation reconcile(YearMonth period) {
        var simulation = simulations.simulate(period); var totals = new TreeMap<String, Long>(); var incidents = new TreeMap<String, String>();
        simulation.invoicesPreview().forEach(invoice -> totals.merge(invoice.memberId(), invoice.total().amountMinor(), Long::sum));
        simulation.incidents().forEach(incident -> incidents.put(incident.memberId(), incident.code().name()));
        return new Reconciliation(simulation.kpis().total().amountMinor(), totals, incidents);
    }
    private static String id(String entity, String source) {
        return UUID.nameUUIDFromBytes((TenantContext.require() + ":playoff:" + entity + ":" + source).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
