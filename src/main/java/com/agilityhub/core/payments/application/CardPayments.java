package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.ports.CardChargingPort;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** R-12-13/18: durable attempts before network calls; batch size 25, stable domain idempotency keys and webhook-owned settlement. */
@Service
public class CardPayments implements CardChargingPort {
    @org.springframework.beans.factory.annotation.Autowired private PaymentAudits audit;
    private final InvoiceRepository invoices; private final CollectionRepository collections; private final BillingRunRepository runs;
    private final PaymentOperationRepository operations; private final PaymentProviderRegistry provider; private final BillingCensusAccess census;
    private final BillingTransactions tx; private final BillingEvents events; private final ClubConfigService configs; private final Clock clock;
    public CardPayments(InvoiceRepository invoices, CollectionRepository collections, BillingRunRepository runs, PaymentOperationRepository operations,
            PaymentProviderRegistry provider, BillingCensusAccess census, BillingTransactions tx, BillingEvents events, ClubConfigService configs, Clock clock) {
        this.invoices = invoices; this.collections = collections; this.runs = runs; this.operations = operations; this.provider = provider;
        this.census = census; this.tx = tx; this.events = events; this.configs = configs; this.clock = clock;
    }
    @Override public Result chargeRun(String runId) {
        provider.require(PaymentProvider.Capability.OFF_SESSION);
        record Prepared(List<String> operations, List<Skip> skipped) { }
        var prepared = tx.run(() -> {
            IdempotentOperation.lock();
            var run = runs.findById(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var prior = runs.cardRequest(runId, IdempotentOperation.reference());
            if (prior != null) { return new Prepared((List<String>) prior.get("operations"),
                    ((List<org.bson.Document>) prior.get("skipped")).stream().map(d -> new Skip(d.getString("invoiceId"), d.getString("reason"))).toList()); }
            if (!runs.charging(runId)) { throw new ApiException(ErrorCode.INVALID_STATE); }
            var queued = new ArrayList<String>(); var skipped = new ArrayList<Skip>();
            for (String id : run.invoiceIds()) {
                String outcome = prepare(id);
                if ("NO_PAYMENT_METHOD".equals(outcome)) { skipped.add(new Skip(id, outcome)); }
                else if (outcome != null) { queued.add(outcome); }
            }
            runs.cardRequest(runId, IdempotentOperation.reference(), queued, skipped.stream()
                    .map(skip -> new org.bson.Document("invoiceId", skip.invoiceId()).append("reason", skip.reason())).toList());
            audit.started(runId, queued.size(), skipped.size());
            progress(runId);
            return new Prepared(queued, skipped);
        });
        // Preparation and the rollback fence commit together, before any provider call.
        for (int start = 0; start < prepared.operations().size(); start += 25) {
            for (String operation : prepared.operations().subList(start, Math.min(start + 25, prepared.operations().size()))) { execute(operation); }
        }
        return new Result(prepared.operations().size(), List.copyOf(prepared.skipped()));
    }
    private String prepare(String id) {
        var invoice = invoice(id);
        if (invoice.paymentMethod().type() != PaymentMethodType.CARD || invoice.status() == InvoiceStatus.CANCELLED) { return null; }
        var attempts = collections.forInvoice(id);
        if (invoice.status() != InvoiceStatus.PENDING) {
            return operations.forTarget(id).stream().filter(op -> op.kind().equals("CHARGE") && op.resultId() == null).map(PaymentOperation::id).findFirst().orElse(null);
        }
        var current = attempts.stream().filter(c -> c.provider() == CollectionProvider.STRIPE && c.status() == CollectionStatus.CREATED).findFirst().orElseThrow();
        var card = census.card(invoice.memberId()).filter(BillingCensusAccess.Card::usable);
        if (card.isEmpty()) {
            collections.resolve(current.id(), CollectionStatus.FAILED, "NO_PAYMENT_METHOD", clock.instant());
            failed(invoice, "NO_PAYMENT_METHOD", clock.instant()); return "NO_PAYMENT_METHOD";
        }
        return queue(invoice, current, card.get(), id);
    }
    public String retry(String id, long version) {
        provider.require(PaymentProvider.Capability.OFF_SESSION);
        return tx.run(() -> {
            IdempotentOperation.lock();
            var prior = operations.forRequest(IdempotentOperation.reference());
            if (prior.isPresent()) { return prior.get().id(); }
            var invoice = invoice(id);
            if (invoice.version() != version) { throw new ApiException(ErrorCode.STALE_VERSION); }
            if (invoice.status() != InvoiceStatus.FAILED) { throw new ApiException(ErrorCode.INVALID_STATE); }
            var card = census.card(invoice.memberId()).filter(BillingCensusAccess.Card::usable).orElseThrow(() -> new ApiException(ErrorCode.NO_PAYMENT_METHOD));
            int attempt = collections.forInvoice(id).stream().mapToInt(Collection::attempt).max().orElse(0) + 1;
            int max = configs.get(TenantContext.require()).get("billing.stripeMaxAttempts", Integer.class);
            if (attempt > max) { throw new ApiException(ErrorCode.MAX_ATTEMPTS, Map.of("attempts", attempt - 1, "max", max)); }
            var collection = new Collection(UUID.randomUUID().toString(), TenantContext.require(), id, CollectionProvider.STRIPE, invoice.total(),
                    CollectionStatus.CREATED, null, null, attempt, null, null, List.of(), clock.instant(), null);
            collections.insert(collection);
            String operation = queue(invoice, collection, card, id + ":" + attempt);
            operations.requestReference(operation, IdempotentOperation.reference());
            if (invoice.runId() != null) { runs.resume(invoice.runId()); }
            return operation;
        });
    }
    private String queue(Invoice invoice, Collection collection, BillingCensusAccess.Card card, String key) {
        String id = UUID.randomUUID().toString();
        var request = new PaymentProvider.OffSessionRequest(invoice.total(), card.customerId(), card.paymentMethodId(), key,
                Map.of("clubId", invoice.clubId(), "invoiceId", invoice.id(), "collectionId", collection.id()));
        operations.insert(new PaymentOperation(id, invoice.clubId(), "CHARGE", invoice.id(), collection.id(), invoice.total(), key, null,
                BillingEvents.actor(), request, clock.instant(), null));
        collections.resolve(collection.id(), CollectionStatus.SUBMITTED, null, null);
        move(invoice, InvoiceState.of(invoice, clock.instant(), BillingEvents.actor()).status(InvoiceStatus.COLLECTING));
        events.publish(BillingEvent.Kind.InvoiceCollecting, invoice.id(), Map.of("invoiceId", invoice.id(), "provider", "STRIPE", "collectionId", collection.id()));
        return id;
    }
    public void execute(String operationId) {
        var operation = operations.findById(operationId).orElseThrow();
        if (operation.resultId() != null) { return; }
        var result = provider.createOffSessionPayment(operation.charge());
        tx.run(() -> {
            collections.submitted(operation.providerRef(), result.paymentIntentId());
            operations.completed(operationId, result.paymentIntentId());
            if ("failed".equals(result.status()) || "requires_action".equals(result.status())) {
                resolve(collections.findById(operation.providerRef()).orElseThrow(), false,
                        result.failureCode() == null ? "authentication_required" : result.failureCode(), clock.instant());
            }
            return null;
        });
    }
    public boolean settle(String paymentIntentId, String collectionId, boolean success, String code, Instant at) {
        var collection = collections.byProviderReference(paymentIntentId).orElseGet(() -> collectionId == null ? null : collections.findById(collectionId).orElse(null));
        if (collection == null || collection.provider() != CollectionProvider.STRIPE) { throw new ApiException(ErrorCode.NOT_FOUND); }
        if (collection.providerRef() != null && !collection.providerRef().equals(paymentIntentId)) { throw new ApiException(ErrorCode.INVALID_STATE); }
        collections.submitted(collection.id(), paymentIntentId);
        return resolve(collection, success, code, at);
    }
    private boolean resolve(Collection collection, boolean success, String code, Instant at) {
        if (collection.status() == CollectionStatus.REFUNDED || collection.status() == CollectionStatus.SUCCEEDED) { return false; }
        var invoice = invoice(collection.invoiceId());
        if (invoice.status() == InvoiceStatus.CANCELLED) { return false; }
        if (!success && invoice.status() != InvoiceStatus.PAID && "expired_card".equals(code)) {
            operations.forTarget(invoice.id()).stream().filter(op -> collection.id().equals(op.providerRef()) && op.charge() != null).findFirst().ifPresent(op -> {
                for (String member : census.invalidateCards(op.charge().customerId(), op.charge().paymentMethodId())) {
                    events.publish(BillingEvent.Kind.MemberCardInvalidated, member, Map.of("memberId", member, "reason", "NO_PAYMENT_METHOD"));
                }
            });
        }
        if (!success && collection.status() == CollectionStatus.FAILED) { return false; }
        collections.resolve(collection.id(), success ? CollectionStatus.SUCCEEDED : CollectionStatus.FAILED, success ? null : code, at);
        // A failure of an earlier attempt never changes a paid receipt or the current attempt.
        int latest = collections.forInvoice(invoice.id()).stream().mapToInt(Collection::attempt).max().orElse(1);
        if (success) {
            if (invoice.status() != InvoiceStatus.PAID) {
                move(invoice, InvoiceState.of(invoice, clock.instant(), null).status(InvoiceStatus.PAID).paid(at));
                events.publish(BillingEvent.Kind.InvoicePaid, invoice.id(), Map.of("invoiceId", invoice.id(), "provider", "STRIPE", "paidAt", at));
            }
        } else if (invoice.status() != InvoiceStatus.PAID && collection.attempt() == latest) { failed(invoice, code, at); }
        progress(invoice.runId()); return true;
    }
    private void failed(Invoice invoice, String reason, Instant at) {
        move(invoice, InvoiceState.of(invoice, clock.instant(), null).status(InvoiceStatus.FAILED).failed(at, reason));
        events.publish(BillingEvent.Kind.InvoiceFailed, invoice.id(), Map.of("invoiceId", invoice.id(), "provider", "STRIPE", "reason", reason));
    }
    private void progress(String runId) {
        if (runId == null) { return; }
        var rows = invoices.forRun(runId).stream().filter(i -> i.paymentMethod().type() == PaymentMethodType.CARD).toList();
        int paid = (int) rows.stream().filter(i -> i.status() == InvoiceStatus.PAID).count();
        int failed = (int) rows.stream().filter(i -> i.status() == InvoiceStatus.FAILED).count();
        boolean done = rows.stream().noneMatch(i -> i.status() == InvoiceStatus.PENDING || i.status() == InvoiceStatus.COLLECTING);
        if (runs.progress(runId, paid, failed, done, clock.instant()) && done) {
            events.publish(BillingEvent.Kind.BillingRunCompleted, runId, Map.of("runId", runId, "period", runs.findById(runId).orElseThrow().period()));
        }
    }
    private Invoice invoice(String id) { return invoices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    private void move(Invoice invoice, InvoiceState state) {
        if (!invoices.transition(invoice.id(), invoice.version(), state)) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
}
