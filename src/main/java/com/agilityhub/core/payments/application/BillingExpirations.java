package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** P5a and P5i, behind the common job's application adapter. */
@Service
public class BillingExpirations {
    private final PackBalanceRepository packs; private final PackBalanceService balances;
    private final RemittanceRepository remittances; private final InvoiceRepository invoices;
    private final CollectionRepository collections; private final BillingEvents events; private final Clock clock;
    public BillingExpirations(PackBalanceRepository packs, PackBalanceService balances, RemittanceRepository remittances,
            InvoiceRepository invoices, CollectionRepository collections, BillingEvents events, Clock clock) {
        this.packs = packs; this.balances = balances; this.remittances = remittances; this.invoices = invoices;
        this.collections = collections; this.events = events; this.clock = clock;
    }
    public List<JobItem> packs(JobContext context) {
        int warning = context.parameter("billing.packExpiryWarningDays", Integer.class);
        return packs.of(null, null).stream().filter(p -> p.state() == PackBalanceState.ACTIVE)
                .filter(p -> LocalDate.parse(p.expiresOn()).isBefore(context.localDate())
                        || p.expiryWarnedAt() == null && !LocalDate.parse(p.expiresOn()).isAfter(context.localDate().plusDays(warning)))
                .map(p -> new JobItem("PackBalance", p.id(), LocalDate.parse(p.expiresOn()).isBefore(context.localDate()) ? "EXPIRE_PACK" : "WARN_PACK"))
                .toList();
    }
    public List<JobItem> remittances(JobContext context) {
        return remittances.findAll().stream().filter(r -> due(r, context.localDate())).map(r -> {
            var due = unsettled(r); return new JobItem("Remittance", r.id(), "SETTLE",
                    Map.of("remittanceId", r.id(), "invoices", due.size()));
        }).filter(item -> (int) item.detail().get("invoices") > 0).toList();
    }
    private boolean due(Remittance remittance, LocalDate today) {
        return remittance.status() == RemittanceStatus.SUBMITTED && !LocalDate.parse(remittance.requestedCollectionDate()).isAfter(today);
    }
    private List<com.agilityhub.core.payments.persistence.Collection> unsettled(Remittance remittance) {
        return remittance.collectionIds().stream().map(collections::findById).flatMap(Optional::stream)
                .filter(c -> remittance.id().equals(c.remittanceId()) && c.provider() == CollectionProvider.SEPA_XML
                        && (c.status() == CollectionStatus.CREATED || c.status() == CollectionStatus.SUBMITTED))
                .filter(c -> invoices.findById(c.invoiceId()).map(i -> i.status() == InvoiceStatus.COLLECTING
                        && remittance.id().equals(i.remittanceId())).orElse(false)).toList();
    }
    public JobEffect apply(JobContext context, JobItem item) {
        if (item.action().equals("SETTLE")) { return settle(context, item); }
        if (item.action().equals("WARN_PACK")) {
            return balances.warnExpiry(item.entityId(), context.localDate(), context.parameter("billing.packExpiryWarningDays", Integer.class))
                    ? new JobEffect(item.action(), item.detail(), Map.of("warnedPacks", 1L)) : noEffect();
        }
        var pack = packs.findById(item.entityId()).orElse(null);
        if (pack == null || pack.state() != PackBalanceState.ACTIVE || !LocalDate.parse(pack.expiresOn()).isBefore(context.localDate())) { return noEffect(); }
        balances.expire(pack.id()); return new JobEffect(item.action(), item.detail(), Map.of("expiredPacks", 1L));
    }
    private JobEffect settle(JobContext context, JobItem item) {
        var remittance = remittances.findById(item.entityId()).orElse(null);
        if (remittance == null || !due(remittance, context.localDate())) { return noEffect(); }
        long count = 0; var paidAt = LocalDate.parse(remittance.requestedCollectionDate()).atStartOfDay(context.zone()).toInstant();
        for (var collection : unsettled(remittance)) {
            var invoice = invoices.findById(collection.invoiceId()).orElseThrow();
            if (!invoices.transition(invoice.id(), invoice.version(), InvoiceState.of(invoice, clock.instant(), null).status(InvoiceStatus.PAID).paid(paidAt))) {
                throw new ApiException(ErrorCode.STALE_VERSION);
            }
            collections.resolve(collection.id(), CollectionStatus.SUCCEEDED, null, paidAt);
            events.publish(BillingEvent.Kind.InvoicePaid, invoice.id(), Map.of("invoiceId", invoice.id(), "memberId", invoice.memberId(),
                    "provider", "SEPA_XML", "paidAt", paidAt)); count++;
        }
        return new JobEffect("SETTLE", Map.of("remittanceId", remittance.id(), "invoices", count), Map.of("settledInvoices", count));
    }
    private static JobEffect noEffect() { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
}
