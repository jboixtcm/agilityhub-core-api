package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.application.ports.RemittanceWriterPort;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.platform.application.InvoiceCounters;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-11/12/14 (E8-T02): the month's generation and its rollback, each one Mongo transaction (with its outbox events and
 * its single audit entry) under the club's billing lock.
 *
 * <p>Generation: one live run per month (`409 RUN_EXISTS`); the month's simulation made after the last relevant change —
 * members, family groups, plans and prices, `billing.*` parameters, the club's configuration (`409 SIMULATION_STALE`); the
 * invoices numbered in the members' order from the series counter ({@link InvoiceNumbers}), one `Collection` each
 * (`SEPA_XML` → the remittance, the invoice `COLLECTING`; `MANUAL` and `STRIPE` → `PENDING`), the club's `PENDING` manual
 * `SEPA_DD` receipts with `includeInNextRun` put into the same remittance (R-12-19: a new `SEPA_XML` attempt each, the receipt
 * `COLLECTING`), the remittance written by {@link RemittanceWriterPort} before the commit, the included members'
 * `nextInvoiceDate` advanced (the old dates kept in `previousDates`), the `PendingCharge`s stamped; `InvoiceIssued` per
 * invoice, `InvoiceCollecting` per remitted receipt, `RemittanceGenerated`, `BillingRunCreated`, one `REMITTANCE_GENERATED`
 * entry with `details.invoiceIds`. Members with an incident are skipped (`skipped[]`) and never block.
 *
 * <p>Rollback: allowed while the remittance is not submitted, no non-manual collection of the run is submitted or
 * succeeded, no receipt of the run (or of its remittance) is paid, the run is not `COMPLETED` and nothing was numbered after
 * the run's block — else `409 RUN_NOT_ROLLBACKABLE {reasons}`. The invoices are cancelled (`ROLLBACK`) with a new
 * `FAILED{ROLLBACK}` collection each, the manual receipts the run remitted go back to `PENDING` (not cancelled: the run did
 * not issue them; `includeInNextRun` kept), the remittance rolled back (its file kept), the counter given back, the dates
 * restored, the charges unbilled: the same month can be simulated and generated again with the same numbers.
 */
@Service
public class BillingRunService {
    /** The word D6's confirmation asks to type (R-12-14). */
    public static final String CONFIRMATION = "RETROCEDIR";
    public static final String ROLLBACK = BillingDocuments.ROLLBACK;

    /** A generated run with its remittance (null without `SEPA_XML` invoices) and what blocks its rollback now. */
    public record RunOutcome(BillingRun run, Remittance remittance, List<RollbackBlocker> blockers) { }
    public record RollbackOutcome(int cancelledInvoices, int restoredMembers) { }

    private final InvoicingService invoicing; private final BillingSimulationService simulator; private final BillingRunRepository runs;
    private final BillingSimulationRepository simulations; private final InvoiceRepository invoices; private final CollectionRepository collections;
    private final RemittanceRepository remittances; private final PendingChargeRepository charges; private final RemittanceWriterPort writer;
    private final InvoiceCounters counters; private final InvoiceNumbers numbers; private final BillingCensusAccess census; private final BillingCatalogAccess catalog;
    private final BillingEvents events; private final AuditWriter audit; private final BillingTransactions transactions; private final Clock clock;
    public BillingRunService(InvoicingService invoicing, BillingSimulationService simulator, BillingRunRepository runs, BillingSimulationRepository simulations,
            InvoiceRepository invoices, CollectionRepository collections, RemittanceRepository remittances, PendingChargeRepository charges,
            RemittanceWriterPort writer, InvoiceCounters counters, InvoiceNumbers numbers, BillingCensusAccess census, BillingCatalogAccess catalog,
            BillingEvents events, AuditWriter audit, BillingTransactions transactions, Clock clock) {
        this.invoicing = invoicing; this.simulator = simulator; this.runs = runs; this.simulations = simulations; this.invoices = invoices;
        this.collections = collections; this.remittances = remittances; this.charges = charges; this.writer = writer; this.counters = counters;
        this.numbers = numbers; this.census = census; this.catalog = catalog; this.events = events; this.audit = audit; this.transactions = transactions;
        this.clock = clock;
    }

    /** R-12-11: {@code work} under the club's billing lock (`409 BILLING_BUSY` while a simulation, run or rollback holds it). */
    public <T> T locked(Supplier<T> work) { return simulator.locked("run", work); }

    /** R-12-11/12: the month's generation; call it inside {@link #locked} and a billing transaction. */
    public RunOutcome generate(YearMonth period, String simulationId, LocalDate requestedCollectionDate) {
        if (runs.live(period.toString()).isPresent()) { throw new ApiException(ErrorCode.RUN_EXISTS); }
        var simulation = simulations.findById(simulationId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!simulation.period().equals(period.toString()) || stale(simulation)) { throw new ApiException(ErrorCode.SIMULATION_STALE); }
        var plan = invoicing.plan(period);
        var context = plan.context(); var drafts = plan.month().invoices();
        if (drafts.isEmpty()) { throw new ApiException(ErrorCode.NO_INVOICES); }
        var issueDate = context.issueDate(); Instant now = clock.instant(); String actor = BillingEvents.actor();
        // R-12-19: the manual SEPA_DD receipts waiting for «the next remittance» join this run's, while SEPA_XML is enabled.
        var included = context.settings().enabledMethods().contains(PaymentMethodType.SEPA_DD) ? invoices.forNextRun() : List.<Invoice>of();
        boolean sepa = !included.isEmpty() || drafts.stream().anyMatch(draft -> method(plan, draft) == PaymentMethodType.SEPA_DD);
        LocalDate collectionDate = null;
        if (sepa) {
            Integer day = context.parameter("billing.sepa.collectionDayOfMonth", Integer.class);
            collectionDate = requestedCollectionDate != null ? requestedCollectionDate : CollectionDates.defaultDate(period, day == null ? 1 : day);
            if (CollectionDates.tooSoon(collectionDate, issueDate)) {
                throw new ApiException(ErrorCode.COLLECTION_DATE_TOO_SOON, Map.of("requested", collectionDate.toString(),
                        "earliest", CollectionDates.earliest(issueDate).toString()));
            }
        }
        // R-12-08: the series of the issue date, numbered from its counter in the members' order.
        var block = numbers.reserve(context, drafts.size());
        String series = block.series(), counterKey = block.counterKey(); long first = block.first();
        String runId = UUID.randomUUID().toString(), remittanceId = sepa ? UUID.randomUUID().toString() : null;
        var issued = new ArrayList<Invoice>(); var attempts = new ArrayList<Collection>(); var sepaAttempts = new ArrayList<Collection>();
        var byMethod = new EnumMap<PaymentMethodType, List<Money>>(PaymentMethodType.class);
        for (int i = 0; i < drafts.size(); i++) {
            var draft = drafts.get(i); var payer = plan.member(draft.payerId()); var method = method(plan, draft);
            long number = first + i;
            var invoice = invoice(UUID.randomUUID().toString(), context, series, number, issueDate, period, payer, draft.lines(), method,
                    method == PaymentMethodType.SEPA_DD ? InvoiceStatus.COLLECTING : InvoiceStatus.PENDING, InvoiceKind.PERIODIC, runId,
                    method == PaymentMethodType.SEPA_DD ? remittanceId : null, false, null, now, actor);
            issued.add(invoice);
            var attempt = firstAttempt(invoice, payer, remittanceId, now);
            attempts.add(attempt);
            if (attempt.provider() == CollectionProvider.SEPA_XML) { sepaAttempts.add(attempt); }
            byMethod.computeIfAbsent(method, ignored -> new ArrayList<>()).add(invoice.total());
        }
        // R-12-19: each waiting manual receipt gets a new SEPA_XML attempt in this remittance and goes COLLECTING.
        var remitted = new ArrayList<Invoice>(); var remittedAttempts = new ArrayList<Collection>();
        for (var receipt : included) {
            var attempt = remittedAttempt(receipt, collections.forInvoice(receipt.id()), remittanceId, now);
            collections.insert(attempt);
            sepaAttempts.add(attempt); remittedAttempts.add(attempt);
            var state = InvoiceState.of(receipt, now, actor).status(InvoiceStatus.COLLECTING).remittance(remittanceId);
            if (!invoices.transition(receipt.id(), receipt.version(), state)) { throw new ApiException(ErrorCode.STALE_VERSION); }
            remitted.add(receipt);
            byMethod.computeIfAbsent(PaymentMethodType.SEPA_DD, ignored -> new ArrayList<>()).add(receipt.total());
        }
        var advances = plan.month().advances();
        var run = new BillingRun(runId, context.clubId(), period.toString(), BillingRunStatus.GENERATED, simulationId, issued.stream().map(Invoice::id).toList(),
                byProvider(byMethod, context, remittanceId), collectionDate == null ? null : collectionDate.toString(), now, now,
                plan.month().skipped().stream().map(skip -> new BillingRun.Skipped(skip.memberId(), BillingSimulationService.name(plan.member(skip.memberId())), skip.code())).toList(),
                advances.stream().map(advance -> new BillingRun.PreviousDate(advance.memberId(), String.valueOf(advance.from()), String.valueOf(advance.to()))).toList(),
                first, actor, null, null, null, now, counterKey);
        issued.forEach(invoices::insert);
        attempts.forEach(collections::insert);
        Remittance remittance = null;
        if (sepa) {
            // R-12-11: the file is written and validated before the commit; a failure here rolls the whole run back.
            var written = writer.write(run, List.copyOf(sepaAttempts), collectionDate);
            remittance = remittances.insert(withId(written, remittanceId));
        }
        for (var advance : advances) {
            if (!census.moveNextInvoiceDate(advance.memberId(), advance.from(), advance.to())) { throw new ApiException(ErrorCode.SIMULATION_STALE); }
        }
        for (int i = 0; i < drafts.size(); i++) {
            for (var line : drafts.get(i).lines()) {
                if (line.chargeId() != null && !charges.bill(line.chargeId(), issued.get(i).id())) { throw new ApiException(ErrorCode.SIMULATION_STALE); }
            }
        }
        var stored = runs.insert(run);
        for (int i = 0; i < issued.size(); i++) {
            var invoice = issued.get(i);
            events.publish(BillingEvent.Kind.InvoiceIssued, invoice.id(), issuedPayload(invoice));
            if (invoice.status() == InvoiceStatus.COLLECTING) {
                events.publish(BillingEvent.Kind.InvoiceCollecting, invoice.id(), Map.of("invoiceId", invoice.id(), "provider", CollectionProvider.SEPA_XML.name(),
                        "collectionId", attempts.get(i).id()));
            }
        }
        for (int i = 0; i < remitted.size(); i++) {
            var receipt = remitted.get(i);
            events.publish(BillingEvent.Kind.InvoiceCollecting, receipt.id(), Map.of("invoiceId", receipt.id(), "provider", CollectionProvider.SEPA_XML.name(),
                    "collectionId", remittedAttempts.get(i).id()));
        }
        var remittedIds = remitted.stream().map(Invoice::id).toList();
        if (remittance != null) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("remittanceId", remittance.id()); payload.put("runId", runId);
            var remittanceInvoices = new ArrayList<>(issued.stream().filter(invoice -> invoice.status() == InvoiceStatus.COLLECTING).map(Invoice::id).toList());
            remittanceInvoices.addAll(remittedIds);
            payload.put("invoiceIds", remittanceInvoices);
            payload.put("fileKey", remittance.fileKey());
            events.publish(BillingEvent.Kind.RemittanceGenerated, remittance.id(), payload);
        }
        events.publish(BillingEvent.Kind.BillingRunCreated, runId, Map.of("runId", runId, "period", period.toString(), "simulationId", simulationId,
                "invoiceCount", issued.size()));
        // R-14-10: one entry for the run, its invoices in `details` (never one entry per invoice); the manual receipts it remitted too.
        audit.write(new AuditCommand(AuditAction.REMITTANCE_GENERATED, "BillingRun", runId, null, null, stored, null),
                Map.of("period", period.toString(), "invoiceIds", stored.invoiceIds(), "remittedManualInvoiceIds", remittedIds,
                        "remittanceId", Objects.toString(remittanceId, "")));
        return new RunOutcome(stored, remittance, blockers(stored));
    }

    /** R-12-14 (call it inside {@link #locked} and a billing transaction). */
    public RollbackOutcome rollback(String runId, String reason, String confirmation) {
        if (!CONFIRMATION.equals(confirmation)) { throw BillingContractAccess.invalid("confirmation"); }
        var run = runs.findById(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var blockers = blockers(run);
        if (run.status() == BillingRunStatus.ROLLED_BACK || !blockers.isEmpty()) {
            throw new ApiException(ErrorCode.RUN_NOT_ROLLBACKABLE, Map.of("reasons", blockers.stream().map(Enum::name).toList()));
        }
        Instant now = clock.instant(); String actor = BillingEvents.actor();
        var issued = invoices.forRun(runId);
        var attempts = collections.forInvoices(issued.stream().map(Invoice::id).toList());
        for (var invoice : issued) {
            if (invoice.status() == InvoiceStatus.CANCELLED) { continue; }
            var state = InvoiceState.of(invoice, now, actor).status(InvoiceStatus.CANCELLED).cancelled(now, ROLLBACK);
            if (!invoices.transition(invoice.id(), invoice.version(), state)) { throw new ApiException(ErrorCode.STALE_VERSION); }
            var last = attempts.stream().filter(attempt -> attempt.invoiceId().equals(invoice.id())).reduce((a, b) -> b).orElse(null);
            collections.insert(new Collection(UUID.randomUUID().toString(), invoice.clubId(), invoice.id(), last == null ? provider(invoice.paymentMethod().type()) : last.provider(),
                    invoice.total(), CollectionStatus.FAILED, null, invoice.remittanceId(), last == null ? 1 : last.attempt(), ROLLBACK, reason, List.of(), now, now,
                    last == null ? null : last.mandateRef(), last == null ? null : last.endToEndId(), null, null));
            events.publish(BillingEvent.Kind.InvoiceCancelled, invoice.id(), Map.of("invoiceId", invoice.id(), "reason", ROLLBACK));
        }
        var remittance = remittances.forRun(runId).orElse(null);
        // R-12-19: the manual receipts the run remitted were not issued by it — back to PENDING, out of the remittance, the flag kept.
        var returned = new ArrayList<String>();
        for (var receipt : remittance == null ? List.<Invoice>of() : invoices.includedIn(remittance.id())) {
            if (receipt.status() != InvoiceStatus.COLLECTING) { continue; }
            var last = collections.forInvoice(receipt.id()).stream().filter(attempt -> remittance.id().equals(attempt.remittanceId())).reduce((a, b) -> b).orElse(null);
            collections.insert(new Collection(UUID.randomUUID().toString(), receipt.clubId(), receipt.id(), CollectionProvider.SEPA_XML, receipt.total(),
                    CollectionStatus.FAILED, null, remittance.id(), last == null ? 1 : last.attempt(), ROLLBACK, reason, List.of(), now, now,
                    last == null ? null : last.mandateRef(), last == null ? null : last.endToEndId(), null, null));
            var state = InvoiceState.of(receipt, now, actor).status(InvoiceStatus.PENDING).remittance(null);
            if (!invoices.transition(receipt.id(), receipt.version(), state)) { throw new ApiException(ErrorCode.STALE_VERSION); }
            returned.add(receipt.id());
        }
        if (remittance != null && !remittances.rollBack(remittance.id())) { throw new ApiException(ErrorCode.STALE_VERSION); }
        if (run.counterKey() != null && !issued.isEmpty()
                && !counters.restore(run.counterKey(), run.firstNumber() + issued.size(), run.firstNumber())) {
            throw new ApiException(ErrorCode.RUN_NOT_ROLLBACKABLE, Map.of("reasons", List.of(RollbackBlocker.MANUAL_INVOICE_AFTER.name())));
        }
        int restored = 0;
        for (var previous : run.previousDates()) {
            if (previous.advancedTo() == null) { continue; }
            if (census.moveNextInvoiceDate(previous.memberId(), LocalDate.parse(previous.advancedTo()), date(previous.nextInvoiceDate()))) { restored++; }
        }
        charges.unbill(issued.stream().map(Invoice::id).toList());
        if (!runs.rollBack(runId, run.version(), now, reason)) { throw new ApiException(ErrorCode.STALE_VERSION); }
        var after = runs.findById(runId).orElseThrow();
        var ids = issued.stream().map(Invoice::id).toList();
        if (remittance != null) {
            var touched = new ArrayList<>(ids); touched.addAll(returned);
            events.publish(BillingEvent.Kind.RemittanceRolledBack, remittance.id(), Map.of("remittanceId", remittance.id(), "runId", runId, "invoiceIds", touched));
        }
        audit.write(new AuditCommand(AuditAction.REMITTANCE_ROLLED_BACK, "BillingRun", runId, null, run, after, reason),
                Map.of("period", run.period(), "invoiceIds", ids, "returnedManualInvoiceIds", returned, "restoredMembers", restored));
        return new RollbackOutcome(ids.size(), restored);
    }

    /**
     * R-12-14: what keeps {@code run} from being rolled back now (empty = rollbackable, when the run is live). A `COMPLETED` run
     * (E8-T04: its cards charged) is never rolled back (§5): `COLLECTION_SUBMITTED`. «Something numbered after the run's block»
     * is read both on the run's counter and on the series, so a receipt numbered by the other counter key (a toggled
     * `billing.invoiceResetYearly`) blocks it too.
     */
    public List<RollbackBlocker> blockers(BillingRun run) {
        if (run.status() == BillingRunStatus.ROLLED_BACK) { return List.of(); }
        var reasons = new ArrayList<RollbackBlocker>();
        var remittance = remittances.forRun(run.id()).orElse(null);
        if (remittance != null && remittance.status() == RemittanceStatus.SUBMITTED) { reasons.add(RollbackBlocker.REMITTANCE_SUBMITTED); }
        var issued = invoices.forRun(run.id());
        // The manual receipts the run put into its remittance (R-12-19) count as the run's for a collection or a payment.
        var touched = new ArrayList<>(issued);
        if (remittance != null) { touched.addAll(invoices.includedIn(remittance.id())); }
        var attempts = collections.forInvoices(touched.stream().map(Invoice::id).toList());
        if (run.status() == BillingRunStatus.COMPLETED || attempts.stream().anyMatch(attempt -> attempt.provider() != CollectionProvider.MANUAL
                && (attempt.status() == CollectionStatus.SUBMITTED || attempt.status() == CollectionStatus.SUCCEEDED))) {
            reasons.add(RollbackBlocker.COLLECTION_SUBMITTED);
        }
        if (touched.stream().anyMatch(invoice -> invoice.status() == InvoiceStatus.PAID)) { reasons.add(RollbackBlocker.INVOICE_PAID); }
        long after = run.firstNumber() + issued.size();
        if (run.counterKey() != null && (counters.next(run.counterKey()).map(next -> next != after).orElse(true)
                || !issued.isEmpty() && invoices.numberedFrom(issued.getFirst().series(), after))) {
            reasons.add(RollbackBlocker.MANUAL_INVOICE_AFTER);
        }
        return reasons;
    }

    /**
     * R-12-07: something billing reads changed after the simulation was taken — the members, family groups, plans and
     * prices, the `billing.*` parameters, and the club's own configuration (its payment providers and modules, round 2).
     */
    boolean stale(BillingSimulation simulation) {
        var involved = new HashSet<String>();
        simulation.incidents().forEach(incident -> involved.add(incident.memberId()));
        simulation.invoicesPreview().forEach(preview -> involved.add(preview.memberId()));
        simulation.cashMembers().forEach(cash -> involved.add(cash.memberId()));
        var changes = new ArrayList<Instant>();
        census.lastChange(involved).ifPresent(changes::add);
        catalog.lastChange().ifPresent(changes::add);
        counters.parametersChangedAt("billing.").ifPresent(changes::add);
        counters.clubChangedAt().ifPresent(changes::add);
        return changes.stream().anyMatch(change -> change.isAfter(simulation.at()));
    }

    /** A new invoice (R-12-08/09): lines numbered from 1, amounts summed from the lines, the payment method frozen and masked. */
    static Invoice invoice(String id, InvoicingService.Context context, String series, long number, LocalDate issueDate, YearMonth period, BillingMember member,
            List<InvoicingRules.Line> lines, PaymentMethodType method, InvoiceStatus status, InvoiceKind kind, String runId, String remittanceId,
            boolean includeInNextRun, String note, Instant now, String actor) {
        String currency = context.currency();
        var stored = new ArrayList<Invoice.Line>();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i); var amounts = line.amounts();
            stored.add(new Invoice.Line(i + 1, line.origin(), line.priceId(), line.bookingId(), line.description(), amounts.base(), amounts.taxPercent(),
                    amounts.tax(), amounts.total()));
        }
        var payment = member.paymentMethod();
        String taxId = payment != null && payment.holderTaxId() != null ? payment.holderTaxId() : member.taxId();
        Money zero = new Money(0, currency);
        return new Invoice(id, context.clubId(), series, number, InvoiceNumbering.displayNumber(series, number), issueDate.toString(), period.toString(), member.id(),
                new Invoice.MemberSnapshot(member.memberNumber(), member.fullName(), taxId), stored,
                InvoiceAmounts.sum(stored.stream().map(Invoice.Line::base).toList(), currency), InvoiceAmounts.sum(stored.stream().map(Invoice.Line::tax).toList(), currency),
                InvoiceAmounts.sum(stored.stream().map(Invoice.Line::total).toList(), currency), snapshot(method, payment), status, kind, runId, remittanceId,
                includeInNextRun, note, null, null, null, null, null, zero, null, null, now, actor, now, actor);
    }
    static Invoice.PaymentMethodSnapshot snapshot(PaymentMethodType method, BillingCensusAccess.PaymentMethod payment) {
        if (payment == null) { return new Invoice.PaymentMethodSnapshot(method, null, null, null, null, null); }
        return new Invoice.PaymentMethodSnapshot(method, method == PaymentMethodType.MANUAL ? null : payment.maskedAccount(), payment.holderName(),
                method == PaymentMethodType.SEPA_DD ? payment.mandateRef() : null, method == PaymentMethodType.CARD ? payment.last4() : null,
                method == PaymentMethodType.MANUAL ? channel(payment.channel()) : null);
    }
    static ManualChannel channel(String stored) {
        if (stored == null) { return null; }
        try { return ManualChannel.valueOf(stored.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return null; }
    }
    static CollectionProvider provider(PaymentMethodType method) {
        return switch (method) { case SEPA_DD -> CollectionProvider.SEPA_XML; case CARD -> CollectionProvider.STRIPE; case MANUAL -> CollectionProvider.MANUAL; };
    }
    static Map<String, Object> issuedPayload(Invoice invoice) {
        return Map.of("invoiceId", invoice.id(), "memberId", invoice.memberId(), "period", invoice.period(), "total", invoice.total(),
                "paymentMethodType", invoice.paymentMethod().type().name(), "kind", invoice.kind().name());
    }
    private static Collection firstAttempt(Invoice invoice, BillingMember payer, String remittanceId, Instant now) {
        var provider = provider(invoice.paymentMethod().type());
        boolean sepa = provider == CollectionProvider.SEPA_XML;
        return new Collection(UUID.randomUUID().toString(), invoice.clubId(), invoice.id(), provider, invoice.total(), CollectionStatus.CREATED, null,
                sepa ? remittanceId : null, 1, null, null, List.of(), now, null, sepa ? invoice.paymentMethod().mandateRef() : null,
                sepa ? invoice.displayNumber() : null, provider == CollectionProvider.MANUAL ? invoice.paymentMethod().channel() : null, null);
    }
    /** R-12-19: a waiting manual receipt's attempt in the run's remittance — its next attempt number, `EndToEndId` its number. */
    private static Collection remittedAttempt(Invoice receipt, List<Collection> earlier, String remittanceId, Instant now) {
        int attempt = earlier.isEmpty() ? 1 : earlier.getLast().attempt() + 1;
        return new Collection(UUID.randomUUID().toString(), receipt.clubId(), receipt.id(), CollectionProvider.SEPA_XML, receipt.total(), CollectionStatus.CREATED, null,
                remittanceId, attempt, null, null, List.of(), now, null, receipt.paymentMethod().mandateRef(), receipt.displayNumber(), null, null);
    }
    private static PaymentMethodType method(InvoicingService.MonthPlan plan, InvoicingRules.Draft draft) {
        return InvoicingService.member(plan.member(draft.payerId())).method();
    }
    private static BillingRun.ByProvider byProvider(Map<PaymentMethodType, List<Money>> byMethod, InvoicingService.Context context, String remittanceId) {
        var enabled = context.settings().enabledMethods(); String currency = context.currency();
        return new BillingRun.ByProvider(totals(PaymentMethodType.SEPA_DD, byMethod, enabled, currency, remittanceId, null),
                totals(PaymentMethodType.CARD, byMethod, enabled, currency, null, 0), totals(PaymentMethodType.MANUAL, byMethod, enabled, currency, null, null));
    }
    private static BillingRun.Totals totals(PaymentMethodType method, Map<PaymentMethodType, List<Money>> byMethod, Set<PaymentMethodType> enabled, String currency,
            String remittanceId, Integer stripeCounter) {
        var amounts = byMethod.getOrDefault(method, List.of());
        if (amounts.isEmpty() && !enabled.contains(method)) { return null; }
        return new BillingRun.Totals(amounts.size(), InvoiceAmounts.sum(amounts, currency), method == PaymentMethodType.SEPA_DD ? remittanceId : null, stripeCounter, stripeCounter);
    }
    private static Remittance withId(Remittance written, String id) {
        if (id.equals(written.id())) { return written; }
        return new Remittance(id, written.clubId(), written.runId(), written.period(), written.messageId(), written.creationAt(), written.requestedCollectionDate(),
                written.creditor(), written.collectionIds(), written.count(), written.total(), written.sequenceBreakdown(), written.fileKey(), written.xsdValidatedAt(),
                written.xsdValidationSkipped(), written.status(), written.submittedAt(), written.submittedByAccountId(), written.version(), written.createdAt(),
                written.createdByAccountId());
    }
    private static LocalDate date(String value) { return value == null || value.equals("null") ? null : LocalDate.parse(value); }
}
