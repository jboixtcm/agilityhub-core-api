package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S12 §5 invoice state actions of D6's drawer (E8-T02), each its own transaction (the caller's {@link BillingTransactions}),
 * each checking the invoice's `version` where it takes one (`409 STALE_VERSION`), each appending a `Collection` instead of
 * changing one (R-12-10):
 * <ul>
 * <li>R-12-16 «Marcar cobrat»: a `PENDING`/`FAILED` invoice paid by `MANUAL` or `SEPA_DD` → `Collection MANUAL SUCCEEDED`, `PAID`,
 * `InvoicePaid{MANUAL}`, `INVOICE_MARKED_PAID`; in bulk all or none.</li>
 * <li>R-12-17 «impagat (manual)»: a `COLLECTING` SEPA invoice, or a `PAID` one with a SEPA collection (a later bank return) →
 * `Collection FAILED{BANK_RETURN}`, `FAILED`, `InvoiceFailed` (→ N-10), `INVOICE_MARKED_FAILED`; no claim, no block (BR-08).</li>
 * <li>R-12-19: the cancellation of a `PENDING`/`FAILED` invoice, never `PAID`/`COLLECTING`; and the manual adjustment invoice
 * (`kind = MANUAL`, `ADJUSTMENT` lines, positive or negative) numbered from the series counter, `INVOICE_CREATED_MANUAL`.</li>
 * </ul>
 * Days the admin picks are club-local (R-12-30): today's becomes the current instant, an earlier one its start in the club's
 * time zone; a day after today is `400 VALIDATION_ERROR`.
 */
@Service
public class InvoiceActions {
    public static final String BANK_RETURN = "BANK_RETURN";
    /** An invoice with its attempts, oldest first (the drawer). */
    public record InvoiceDetail(Invoice invoice, List<Collection> collections) { }
    public record ManualLine(String description, Money base, BigDecimal taxPercent) { }

    private final InvoiceRepository invoices; private final CollectionRepository collections; private final BillingLockRepository locks;
    private final InvoiceNumbers numbers; private final InvoicingService invoicing; private final BillingCensusAccess census; private final BillingTexts texts;
    private final BillingEvents events; private final AuditWriter audit; private final ClubClock clubClock; private final Clock clock;
    public InvoiceActions(InvoiceRepository invoices, CollectionRepository collections, BillingLockRepository locks, InvoiceNumbers numbers,
            InvoicingService invoicing, BillingCensusAccess census, BillingTexts texts, BillingEvents events, AuditWriter audit, ClubClock clubClock, Clock clock) {
        this.invoices = invoices; this.collections = collections; this.locks = locks; this.numbers = numbers; this.invoicing = invoicing;
        this.census = census; this.texts = texts; this.events = events; this.audit = audit; this.clubClock = clubClock; this.clock = clock;
    }

    public InvoiceDetail detail(String id) {
        var invoice = invoices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new InvoiceDetail(invoice, collections.forInvoice(id));
    }
    /** The member's own language (their signup's), for the receipt the admin downloads (R-12-27, §10). */
    public Optional<String> memberLocale(String memberId) {
        return census.member(memberId).map(BillingCensusAccess.BillingMember::locale).filter(locale -> locale != null && !locale.isBlank());
    }

    /** R-12-16, one invoice. */
    public InvoiceDetail markPaid(String id, LocalDate paidAt, ManualChannel channel, String reference, long version) {
        var invoice = current(id, version);
        return paid(invoice, paidAt, channel, reference);
    }
    /** R-12-16 in bulk: every invoice payable or none (`409 INVALID_STATE`). */
    public List<InvoiceDetail> markPaid(List<String> ids, LocalDate paidAt, ManualChannel channel) {
        var selected = new ArrayList<Invoice>();
        for (String id : new LinkedHashSet<>(ids)) { selected.add(invoices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))); }
        selected.forEach(InvoiceActions::payable);
        return selected.stream().map(invoice -> paid(invoice, paidAt, channel, null)).toList();
    }
    /** R-12-17. */
    public InvoiceDetail markFailed(String id, String reason, LocalDate at, long version) {
        var invoice = current(id, version);
        var attempts = collections.forInvoice(id);
        var sepa = attempts.stream().filter(attempt -> attempt.provider() == CollectionProvider.SEPA_XML).reduce((a, b) -> b).orElse(null);
        boolean collecting = invoice.status() == InvoiceStatus.COLLECTING && (sepa != null || invoice.paymentMethod().type() == PaymentMethodType.SEPA_DD);
        boolean returned = invoice.status() == InvoiceStatus.PAID && sepa != null;
        if (!collecting && !returned) { throw invalidState(invoice); }
        Instant failedAt = instant(at, "at"), now = clock.instant();
        collections.insert(new Collection(UUID.randomUUID().toString(), invoice.clubId(), id, CollectionProvider.SEPA_XML, invoice.total(), CollectionStatus.FAILED, null,
                sepa == null ? invoice.remittanceId() : sepa.remittanceId(), sepa == null ? 1 : sepa.attempt(), BANK_RETURN, reason, List.of(), now, failedAt,
                sepa == null ? invoice.paymentMethod().mandateRef() : sepa.mandateRef(), sepa == null ? invoice.displayNumber() : sepa.endToEndId(), null, null));
        var after = move(invoice, InvoiceState.of(invoice, now, BillingEvents.actor()).status(InvoiceStatus.FAILED).failed(failedAt, reason));
        events.publish(BillingEvent.Kind.InvoiceFailed, id, Map.of("invoiceId", id, "provider", CollectionProvider.SEPA_XML.name(), "reason", BANK_RETURN));
        audit.write(new AuditCommand(AuditAction.INVOICE_MARKED_FAILED, "Invoice", id, invoice.memberId(), invoice, after, reason));
        return new InvoiceDetail(after, collections.forInvoice(id));
    }
    /**
     * R-12-19: `PENDING`/`FAILED` → `CANCELLED{ADMIN}` with the admin's reason. `ROLLBACK` is the reason only a rollback writes
     * (R-12-14): a receipt cancelled with it would read as rolled back and leave the member's list, so the admin may not type
     * it (`400 VALIDATION_ERROR {field: reason}`).
     */
    public InvoiceDetail cancel(String id, String reason, long version) {
        if (reason != null && BillingRunService.ROLLBACK.equalsIgnoreCase(reason.strip())) { throw BillingContractAccess.invalid("reason"); }
        var invoice = current(id, version);
        if (invoice.status() != InvoiceStatus.PENDING && invoice.status() != InvoiceStatus.FAILED) { throw invalidState(invoice); }
        Instant now = clock.instant();
        var after = move(invoice, InvoiceState.of(invoice, now, BillingEvents.actor()).status(InvoiceStatus.CANCELLED).cancelled(now, reason));
        events.publish(BillingEvent.Kind.InvoiceCancelled, id, Map.of("invoiceId", id, "reason", "ADMIN"));
        audit.write(new AuditCommand(AuditAction.INVOICE_CANCELLED, "Invoice", id, invoice.memberId(), invoice, after, reason));
        return new InvoiceDetail(after, collections.forInvoice(id));
    }
    /** R-12-19: a manual adjustment invoice, numbered like the run's (`409 BILLING_BUSY` while a run holds the counter). */
    public InvoiceDetail createManual(String memberId, List<ManualLine> lines, boolean includeInNextRun, String note) {
        if (locks.held(clock.instant())) { throw new ApiException(ErrorCode.BILLING_BUSY); }
        var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var context = invoicing.context();
        var drafted = new ArrayList<InvoicingRules.Line>();
        for (var line : lines) {
            InvoiceAmounts.requireCurrency(line.base(), context.currency());
            drafted.add(new InvoicingRules.Line(InvoiceLineOrigin.ADJUSTMENT, null, null, null, null, texts.adjustment(line.description(), context.locale()),
                    InvoiceAmounts.fromBase(line.base(), line.taxPercent()), null));
        }
        var method = InvoicingService.member(member).method();
        if (method == null) { method = PaymentMethodType.MANUAL; }
        var issueDate = context.issueDate();
        var block = numbers.reserve(context, 1);
        String series = block.series(); long number = block.first();
        Instant now = clock.instant(); String actor = BillingEvents.actor();
        var invoice = invoices.insert(BillingRunService.invoice(UUID.randomUUID().toString(), context, series, number, issueDate, YearMonth.from(issueDate), member,
                drafted, method, InvoiceStatus.PENDING, InvoiceKind.MANUAL, null, null, includeInNextRun && method == PaymentMethodType.SEPA_DD, note, now, actor));
        events.publish(BillingEvent.Kind.InvoiceIssued, invoice.id(), BillingRunService.issuedPayload(invoice));
        audit.write(new AuditCommand(AuditAction.INVOICE_CREATED_MANUAL, "Invoice", invoice.id(), memberId, null, invoice, note));
        return new InvoiceDetail(invoice, List.of());
    }

    private InvoiceDetail paid(Invoice invoice, LocalDate paidAt, ManualChannel channel, String reference) {
        payable(invoice);
        Instant when = instant(paidAt, "paidAt"), now = clock.instant();
        var attempts = collections.forInvoice(invoice.id());
        var last = attempts.isEmpty() ? null : attempts.getLast();
        int attempt = last == null ? 1 : last.provider() == CollectionProvider.MANUAL && last.status() == CollectionStatus.CREATED ? last.attempt() : last.attempt() + 1;
        collections.insert(new Collection(UUID.randomUUID().toString(), invoice.clubId(), invoice.id(), CollectionProvider.MANUAL, invoice.total(), CollectionStatus.SUCCEEDED,
                null, null, attempt, null, null, List.of(), now, when, null, null, channel, reference));
        var after = move(invoice, InvoiceState.of(invoice, now, BillingEvents.actor()).status(InvoiceStatus.PAID).paid(when));
        events.publish(BillingEvent.Kind.InvoicePaid, invoice.id(), Map.of("invoiceId", invoice.id(), "provider", CollectionProvider.MANUAL.name(), "paidAt", when.toString()));
        audit.write(new AuditCommand(AuditAction.INVOICE_MARKED_PAID, "Invoice", invoice.id(), invoice.memberId(), invoice, after, reference));
        return new InvoiceDetail(after, collections.forInvoice(invoice.id()));
    }
    private static void payable(Invoice invoice) {
        boolean state = invoice.status() == InvoiceStatus.PENDING || invoice.status() == InvoiceStatus.FAILED;
        var type = invoice.paymentMethod() == null ? null : invoice.paymentMethod().type();
        if (!state || (type != PaymentMethodType.MANUAL && type != PaymentMethodType.SEPA_DD)) { throw invalidState(invoice); }
    }
    private Invoice current(String id, long version) {
        var invoice = invoices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (invoice.version() == null || invoice.version() != version) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return invoice;
    }
    private Invoice move(Invoice invoice, InvoiceState state) {
        if (!invoices.transition(invoice.id(), invoice.version(), state)) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return invoices.findById(invoice.id()).orElseThrow();
    }
    /** R-12-30: a club-local day — today's is now, an earlier one its start of day; a later one is `400 VALIDATION_ERROR {field}`. */
    private Instant instant(LocalDate day, String field) {
        var now = clubClock.now(TenantContext.require());
        if (day.isAfter(now.toLocalDate())) { throw BillingContractAccess.invalid(field); }
        return day.equals(now.toLocalDate()) ? clock.instant() : day.atStartOfDay(now.getZone()).toInstant();
    }
    private static ApiException invalidState(Invoice invoice) {
        return new ApiException(ErrorCode.INVALID_STATE, Map.of("status", invoice.status().name()));
    }
}
