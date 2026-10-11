package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** R-12-20: reserves refundable amounts transactionally; the provider call is a recoverable command and only the webhook settles it. */
@Service
public class PaymentRefunds {
    @org.springframework.beans.factory.annotation.Autowired private PaymentRetryPolicy retries;
    @org.springframework.beans.factory.annotation.Autowired private PendingChargeRepository charges;
    @org.springframework.beans.factory.annotation.Autowired private StripeRefundRepository refundStates;
    @org.springframework.beans.factory.annotation.Autowired private BillingEvents events;
    @org.springframework.beans.factory.annotation.Autowired private SignupCheckoutRepository checkouts;
    public record Accepted(String id, Money amount, String providerRef) { }
    /**
     * The longest retry backoff: the commands of a disabled club, and the automatic ones an outage of its Stripe access rejected
     * (E8-T11), stay out of the shared pending page meanwhile.
     */
    static final java.time.Duration DEFERRAL = java.time.Duration.ofMinutes(5);
    private final InvoiceRepository invoices; private final CollectionRepository collections; private final UpfrontPaymentRepository upfront;
    private final PaymentOperationRepository operations; private final PaymentProviderRegistry provider; private final BillingTransactions tx;
    private final Clock clock; private final PaymentAudits audit;
    public PaymentRefunds(InvoiceRepository invoices, CollectionRepository collections, UpfrontPaymentRepository upfront, PaymentOperationRepository operations,
            PaymentProviderRegistry provider, BillingTransactions tx, Clock clock, PaymentAudits audit) {
        this.invoices = invoices; this.collections = collections; this.upfront = upfront; this.operations = operations;
        this.provider = provider; this.tx = tx; this.clock = clock; this.audit = audit;
    }
    public Accepted invoice(String id, Money amount, String reason, String key) {
        provider.require(PaymentProvider.Capability.REFUND);
        return tx.run(() -> {
            IdempotentOperation.lock();
            var invoice = invoices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var collection = collections.forInvoice(id).stream().filter(c -> c.provider() == CollectionProvider.STRIPE
                    && (c.status() == CollectionStatus.SUCCEEDED || c.status() == CollectionStatus.REFUNDED)).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
            if (invoice.status() != InvoiceStatus.PAID) { throw new ApiException(ErrorCode.INVALID_STATE); }
            refundStates.lockIntent(collection.providerRef());
            var refunds = collection.refunds() == null ? List.<Collection.Refund>of() : collection.refunds();
            var accepted = reserve("REFUND_INVOICE", id, collection.providerRef(), collection.amount(), amount, reason, key,
                    refunds.stream().map(Collection.Refund::providerRef).toList(), refunds.stream().mapToLong(r -> r.amount().amountMinor()).sum());
            // Conflicts with another reservation or settlement on this invoice, even though the financial fields stay immutable.
            if (!invoices.transition(id, invoice.version(), InvoiceState.of(invoice, clock.instant(), BillingEvents.actor()))) { throw new ApiException(ErrorCode.STALE_VERSION); }
            return accepted;
        });
    }
    public Accepted upfront(String id, Money amount, String reason, String key) {
        provider.require(PaymentProvider.Capability.REFUND);
        return tx.run(() -> {
            IdempotentOperation.lock();
            var payment = upfront.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var prior = operations.byKey(key).filter(op -> op.targetId().equals(id));
            if (prior.isPresent()) { var op = prior.get(); return new Accepted(op.id(), op.amount(), op.resultId()); }
            if (!"STRIPE".equals(payment.provider())) { throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }
            if (!"PAID".equals(payment.status()) || payment.stripe() == null) { throw new ApiException(ErrorCode.INVALID_STATE); }
            refundStates.lockIntent(payment.stripe().paymentIntentId());
            upfront.lock(id);
            var refunds = payment.refunds() == null ? List.<UpfrontPayment.Refund>of() : payment.refunds();
            // The intent ledger accounts for every credit and refund when reserving this row's share.
            var captured = upfront.captured(id).orElse(payment.amountPaid());
            var accepted = reserve("REFUND_UPFRONT", id, payment.stripe().paymentIntentId(), captured,
                    amount, reason, key, refunds.stream().map(UpfrontPayment.Refund::providerRef).toList(), refunds.stream().mapToLong(r -> r.amount().amountMinor()).sum());
            reconsiderCompensation(payment.stripe().paymentIntentId());
            return accepted;
        });
    }
    private Accepted reserve(String kind, String target, String reference, Money paid, Money requested, String reason, String key,
            List<String> settled, long refunded) {
        var existing = operations.forTarget(target);
        var retry = existing.stream().filter(op -> op.key().equals(key)).findFirst();
        if (retry.isPresent()) { var op = retry.get(); return new Accepted(op.id(), op.amount(), op.resultId()); }
        long pending = existing.stream().filter(op -> op.kind().startsWith("REFUND") && !settled.contains(op.resultId())
                && !operations.refundFailed(op.id())).mapToLong(op -> op.amount().amountMinor()).sum();
        long remaining = paid.amountMinor() - refunded - pending;
        if ("REFUND_UPFRONT".equals(kind)) {
            var payment = upfront.findById(target).orElseThrow();
            remaining = intentLedger(reference).freeFor(payment.id());
        }
        Money amount = requested == null ? new Money(remaining, paid.currency()) : requested;
        if (!amount.currency().equals(paid.currency())) { throw new ApiException(ErrorCode.CURRENCY_MISMATCH); }
        if (amount.amountMinor() <= 0 || amount.amountMinor() > remaining) { throw new ApiException(ErrorCode.REFUND_EXCEEDS_PAID); }
        var operation = new PaymentOperation(UUID.randomUUID().toString(), TenantContext.require(), kind, target, reference, amount, key, reason,
                BillingEvents.actor(), null, clock.instant(), null);
        operations.insert(operation);
        return new Accepted(operation.id(), amount, null);
    }
    /** E34: the provider payment, rather than either local path, is the unique refund key. Called inside the completion transaction. */
    public void late(String sessionId, String reference, Money amount) {
        if (reference == null || amount.amountMinor() <= 0) { return; }
        refundStates.lockIntent(reference);
        String key = "late:" + reference;
        if (operations.byKey(key).isPresent()) { return; }
        operations.insert(new PaymentOperation(UUID.randomUUID().toString(), TenantContext.require(), "REFUND_LATE", sessionId, reference,
                amount, key, "LATE_COMPLETION", null, null, clock.instant(), null));
    }
    /** Both cancellation consumers serialize on the payment, record its one obligation and let the ledger say what is still owed. */
    public void compensate(String paymentId, String policy, boolean beforeConfirmation) {
        tx.run(() -> {
            upfront.lock(paymentId);
            var payment = upfront.findById(paymentId).orElseThrow();
            if (payment.stripe() != null) { refundStates.lockIntent(payment.stripe().paymentIntentId()); }
            String chosen = beforeConfirmation ? "REFUND" : policy;
            if (consumption(payment) || !Set.of("REFUND", "CREDIT").contains(chosen)) { return null; }
            upfront.compensation(paymentId, chosen, beforeConfirmation ? "LATE_COMPLETION" : "BOOKING_CANCELLED");
            compensateRemaining(payment);
            return null;
        });
    }
    /**
     * Drives the obligation's due amount (ledger D) to zero, under the payment lock and after the changed refund status is visible
     * in the same transaction. It never calls the provider, so a Stripe reconciliation always commits (ledger invariant 3); the
     * refund command waits for the provider in {@link #execute}. A compensation that cannot proceed becomes an intervention.
     */
    private void compensateRemaining(UpfrontPayment payment) {
        String key = "refund:" + payment.id();
        var obligation = upfront.compensation(payment.id())
                .or(() -> operations.byKey(key).map(op -> new UpfrontPaymentRepository.Compensation("REFUND", op.reason(), null)));
        if (obligation.isEmpty() || consumption(payment)) { return; }
        long due = payment.stripe() == null ? ledger(payment).due()
                : Math.max(0, intentLedger(payment.stripe().paymentIntentId()).freeFor(payment.id()));
        if (obligation.get().interventionAt() != null) { intervene(payment, due); return; }
        if (due <= 0) { return; }
        if ("CREDIT".equals(obligation.get().policy())) { credit(payment, due); return; }
        var commands = operations.forTarget(payment.id()).stream().filter(PaymentRefunds::compensation).toList();
        // Bounded across the obligation (E8-T04 round 2, point 5): a failed compensation refund is terminal, never replaced. Each
        // supplement exists only because a distinct non-compensation refund failed; an outage never fails a compensation (E8-T11).
        if (payment.stripe() == null || commands.stream().anyMatch(op -> operations.refundFailed(op.id()))) {
            intervene(payment, due);
            return;
        }
        operations.insert(new PaymentOperation(UUID.randomUUID().toString(), TenantContext.require(), "REFUND_UPFRONT", payment.id(),
                payment.stripe().paymentIntentId(), new Money(due, payment.amountPaid().currency()), commands.isEmpty() ? key : key + ":" + commands.size(),
                obligation.get().reason(), BillingEvents.actor(), null, clock.instant(), null));
    }
    /** One credit row per booking: written once, grown while unbilled when a failed refund frees money; a billed one needs an admin. */
    private void credit(UpfrontPayment payment, long due) {
        if (payment.bookingId() == null) { intervene(payment, due); return; }
        var existing = charges.forBooking(payment.bookingId());
        if (existing.isEmpty()) {
            charges.insert(new PendingCharge(UUID.randomUUID().toString(), TenantContext.require(), payment.memberId(), payment.dogId(), payment.bookingId(), null,
                    new Money(-due, payment.amountPaid().currency()), payment.concept(), clock.instant(), null, null));
            return;
        }
        long current = existing.get().amount().amountMinor();
        if (!charges.credit(existing.get().id(), current, current - due)) { intervene(payment, due); }
    }
    /** E101: keep the outstanding balance current; only a changed positive total emits another audit and admin notification. */
    private void intervene(UpfrontPayment payment, long owed) {
        var amount = new Money(owed, payment.amountPaid().currency());
        if (!upfront.interventionOwed(payment.id(), amount) || owed <= 0) { return; }
        if (upfront.intervention(payment.id(), clock.instant())) { retries.warnIntervention(payment.id()); }
        reportIntervention(payment, "COMPENSATION_INTERVENTION", "owed", amount);
    }
    private void reportIntervention(UpfrontPayment payment, String reason, String kind, Money amount) {
        audit.intervention(payment.id(), payment.memberId(), reason, kind, amount);
        events.publish(BillingEvent.Kind.UpfrontRefundIntervention, payment.id(), Map.of("paymentId", payment.id(),
                "memberId", payment.memberId(), "reason", reason, "amount", amount));
    }
    /** The obligation's own refund commands: the first, its supplements, and a late confirmation's refund of the same payment. */
    private static boolean compensation(PaymentOperation op) {
        return op.key().equals("refund:" + op.targetId()) || op.key().startsWith("refund:" + op.targetId() + ":");
    }
    /**
     * E8-T11: an automatic refund (a compensation or a late confirmation's) rejected by an outage of the club's Stripe access
     * waits like a disabled provider's, without spending attempts, instead of becoming manual work. An admin refund keeps
     * its bounded attempts and is released: the admin, who asked for it, can ask again.
     */
    private static boolean outage(PaymentOperation op, RuntimeException failure) {
        return (compensation(op) || "REFUND_LATE".equals(op.kind())) && failure instanceof PaymentNotSubmitted rejected && !rejected.definitive();
    }
    /** E105: absorption is a decision of the whole intent; rows only persist the resulting credit reductions. */
    private void absorb(String intent) {
        var ledger = intentLedger(intent);
        for (var reduction : ledger.creditReductions().entrySet()) {
            var payment = upfront.findById(reduction.getKey()).orElseThrow();
            var credit = charges.forBooking(payment.bookingId()).orElseThrow();
            long current = credit.amount().amountMinor();
            boolean changed = current + reduction.getValue() < 0
                    ? charges.credit(credit.id(), current, current + reduction.getValue())
                    : charges.voided(credit.id(), clock.instant());
            // Billing and absorption update the same document. Retry the transaction if its mutable credit changed.
            if (!changed) { throw new ApiException(ErrorCode.STALE_VERSION); }
        }
    }
    private void reportOverpayment(String intent, long previous) {
        var ledger = intentLedger(intent);
        long increment = ledger.overpaid() - previous;
        if (increment <= 0) { return; }
        ledger.rows().stream().filter(row -> row.billed() && row.credited() > 0).findFirst().ifPresent(row -> {
            var payment = upfront.findById(row.id()).orElseThrow();
            if (upfront.intervention(payment.id(), clock.instant())) { retries.warnIntervention(payment.id()); }
            reportIntervention(payment, "REFUND_OVER_CREDIT", "overpaid", new Money(increment, payment.amountPaid().currency()));
        });
    }
    private void reconsiderCompensation(String intent) {
        refundStates.lockIntent(intent);
        for (var late : operations.refundsForIntent(intent).stream().filter(op -> "REFUND_LATE".equals(op.kind())).toList()) { reconsiderLate(late); }
        absorb(intent);
        for (var payment : upfront.forIntent(intent)) {
            upfront.lock(payment.id());
            compensateRemaining(upfront.findById(payment.id()).orElseThrow());
        }
    }
    /** Expired checkouts never attach their late intent to an upfront row. Resolve their own immutable command target. */
    private void reconsiderLate(PaymentOperation operation) {
        if (!operations.refundFailed(operation.id())) { return; }
        var payment = upfront.findById(operation.targetId()).or(() -> checkouts.findById(operation.targetId())
                .flatMap(session -> session.upfrontPaymentIds().stream().map(upfront::findById).flatMap(Optional::stream).findFirst()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE));
        upfront.lock(payment.id());
        long repaid = refundStates.forIntent(operation.providerRef()).stream().filter(state -> Set.of("succeeded", "pending").contains(state.status()))
                .mapToLong(state -> state.amount().amountMinor()).sum();
        var owed = new Money(Math.max(0, operation.amount().amountMinor() - repaid), operation.amount().currency());
        if (!operations.interventionOwed(operation.id(), owed) || owed.amountMinor() <= 0) { return; }
        if (upfront.lateInterventionWarning(payment.id(), clock.instant())) { retries.warnIntervention(payment.id()); }
        reportIntervention(payment, "COMPENSATION_INTERVENTION", "owed", owed);
    }
    private long refunded(UpfrontPayment payment) {
        return payment.refunds() == null ? 0 : payment.refunds().stream().mapToLong(r -> r.amount().amountMinor()).sum();
    }
    private long captured(UpfrontPayment payment) { return upfront.captured(payment.id()).orElse(payment.amountPaid()).amountMinor(); }
    /**
     * The one money view for an intent, always read within its transaction fence. Settled refund shares are persisted in the
     * same transaction as provider checkpoints. Local operations and unlinked pending checkpoints reserve each refund once,
     * including a webhook that closed a lost provider response. Row allocation never supplies the absorption/reporting totals.
     */
    private PaymentIntentLedger intentLedger(String intent) {
        var payments = upfront.forIntent(intent);
        var rows = new ArrayList<PaymentIntentLedger.Row>();
        var settled = new HashSet<String>();
        for (var payment : payments) {
            var credit = payment.bookingId() == null ? null : charges.forBooking(payment.bookingId())
                    .filter(c -> c.amount().amountMinor() < 0 && c.voidedAt() == null).orElse(null);
            rows.add(new PaymentIntentLedger.Row(payment.id(), captured(payment), refunded(payment),
                    credit == null ? 0 : -credit.amount().amountMinor(), credit != null && credit.invoiceId() != null));
            if (payment.refunds() != null) { payment.refunds().forEach(refund -> settled.add(refund.providerRef())); }
        }
        var reservations = new ArrayList<PaymentIntentLedger.Reservation>();
        var linked = new HashSet<String>();
        for (var operation : operations.refundsForIntent(intent)) {
            if (operation.resultId() != null) { linked.add(operation.resultId()); }
            if (!settled.contains(operation.resultId()) && !operations.refundFailed(operation.id())) {
                reservations.add(new PaymentIntentLedger.Reservation(operation.id(), operation.targetId(), operation.amount().amountMinor()));
            }
        }
        for (var state : refundStates.pending(intent)) {
            if (!linked.contains(refundStates.refundId(state))) {
                reservations.add(new PaymentIntentLedger.Reservation(state.id(), null, state.amount().amountMinor()));
            }
        }
        return new PaymentIntentLedger(rows, reservations);
    }
    private boolean compensated(UpfrontPayment payment) {
        return operations.byKey("refund:" + payment.id()).isPresent()
                || Set.of("PAID", "REFUNDED").contains(payment.status()) && ledger(payment).free() <= 0
                || payment.bookingId() != null && charges.forBooking(payment.bookingId()).isPresent();
    }
    /** A non-negative charge on the booking is a consumption charge (R-12-25), never a compensation. */
    private boolean consumption(UpfrontPayment payment) {
        return payment.bookingId() != null && charges.forBooking(payment.bookingId()).filter(c -> c.amount().amountMinor() >= 0).isPresent();
    }
    private long credited(UpfrontPayment payment) {
        if (payment.bookingId() == null) { return 0; }
        return charges.forBooking(payment.bookingId()).filter(c -> c.amount().amountMinor() < 0 && c.voidedAt() == null)
                .map(c -> -c.amount().amountMinor()).orElse(0L);
    }
    /** The ledger of {@link CapturedPaymentLedger}; a settled late-completion command still counts as reserved, as its money left. */
    private CapturedPaymentLedger ledger(UpfrontPayment payment) {
        if (payment.stripe() != null) {
            return intentLedger(payment.stripe().paymentIntentId()).share(payment.id());
        }
        var settled = payment.refunds() == null ? List.<UpfrontPayment.Refund>of() : payment.refunds();
        var ids = settled.stream().map(UpfrontPayment.Refund::providerRef).toList();
        long reserved = operations.forTarget(payment.id()).stream().filter(op -> op.kind().startsWith("REFUND")
                && !ids.contains(op.resultId()) && !operations.refundFailed(op.id())).mapToLong(op -> op.amount().amountMinor()).sum();
        return new CapturedPaymentLedger(upfront.captured(payment.id()).orElse(payment.amountPaid()).amountMinor(),
                settled.stream().mapToLong(r -> r.amount().amountMinor()).sum(), reserved, credited(payment));
    }
    /** A booking has one upfront capture: the late path uses the same payment key as BookingCancelled. */
    public void lateBooking(String paymentId, String reference, Money amount) {
        if (reference == null || amount.amountMinor() <= 0) { return; }
        tx.run(() -> {
            upfront.lock(paymentId);
            refundStates.lockIntent(reference);
            var payment = upfront.findById(paymentId).orElseThrow();
            if (operations.byKey("late:" + reference).isPresent()) { return null; }
            if ("PAID".equals(payment.status()) && payment.stripe() != null && reference.equals(payment.stripe().paymentIntentId())) {
                compensate(paymentId, "REFUND", true);
            } else if (!compensated(payment)) {
                operations.insert(new PaymentOperation(UUID.randomUUID().toString(), TenantContext.require(), "REFUND_LATE", paymentId, reference,
                        amount, "refund:" + paymentId, "LATE_COMPLETION", null, null, clock.instant(), null));
            }
            return null;
        });
    }
    public void executeLate() {
        for (var op : operations.lateRefunds()) {
            try { execute(op.id()); } catch (RuntimeException deferred) { /* The bounded retry checkpoint has been saved. */ }
        }
    }
    public void execute(String id) {
        var op = operations.findById(id).orElseThrow();
        if (op.resultId() != null) { return; }
        if (!provider.supports(PaymentProvider.Capability.REFUND)) {
            // A disabled Stripe postpones the command, keeping its reservation and its attempts (ledger invariant 3).
            tx.run(() -> { operations.defer(id, clock.instant().plus(DEFERRAL)); return null; });
            return;
        }
        retries.execute(op, execution -> {
            var result = provider.refund(op.providerRef(), op.amount(), op.key(), op.reason(), op.id());
            tx.run(() -> {
                execution.fence();
                refundStates.lockIntent(op.providerRef());
                if (operations.findById(id).orElseThrow().resultId() == null) {
                    operations.completed(id, result.id());
                }
                operations.providerStatus(id, result.status());
                return null;
            });
        }, () -> {
            refundStates.lockIntent(op.providerRef());
            // Proven non-execution releases the reservation. Earlier uncertain outcomes still await Stripe.
            if (!operations.submissionUncertain(id)) {
                operations.providerStatus(id, "failed");
                // A rejected refund can free money a cancellation still owes; a refused compensation itself becomes an intervention.
                reconsiderCompensation(op.providerRef());
            }
        }, failure -> outage(op, failure), DEFERRAL);
    }
    /** Refund objects are authoritative. A terminal failure wins over a redelivered older success. */
    public boolean reconcile(String intent, String refundId, Money amount, String status, Instant at, String reason, String operationId) {
        refundStates.lockIntent(intent);
        var previous = refundStates.lock(refundId);
        if (previous.status() != null) {
            if (!Objects.equals(previous.intent(), intent) || !previous.amount().equals(amount)) { throw new ApiException(ErrorCode.INVALID_STATE); }
        }
        var operation = operation(intent, refundId, amount, operationId);
        if (operation != null) { operations.reconciled(operation.id(), refundId); }
        if (previous.status() != null) {
            if (Set.of("failed", "canceled").contains(previous.status()) || previous.status().equals(status)
                    || "succeeded".equals(previous.status()) && !Set.of("failed", "canceled").contains(status)) { return false; }
        }
        // The current checkpoint must be visible to ledger reads in settlement and compensation in this transaction.
        refundStates.outcome(refundId, intent, amount, status, at);
        if (operation != null) { operations.refundStatus(operation.id(), status); }
        if ("succeeded".equals(status)) { settled(intent, refundId, amount, at, reason, operationId); }
        else if (Set.of("failed", "canceled").contains(status)) {
            reverse(intent, refundId, operation, status, "succeeded".equals(previous.status()));
            retries.warnRefund(refundId, status);
        }
        reconsiderCompensation(intent);
        return true;
    }
    private String entityType(PaymentOperation operation) {
        if ("REFUND_INVOICE".equals(operation.kind())) { return "Invoice"; }
        return "REFUND_LATE".equals(operation.kind()) && upfront.findById(operation.targetId()).isEmpty() ? "CheckoutSession" : "UpfrontPayment";
    }
    private PaymentOperation operation(String intent, String refundId, Money amount, String operationId) {
        var operation = operationId == null ? operations.forResult(refundId).orElse(null)
                : operations.findById(operationId).orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE));
        if (operation != null && (!operation.kind().startsWith("REFUND") || !Objects.equals(operation.providerRef(), intent)
                || !operation.amount().equals(amount) || operation.resultId() != null && !operation.resultId().equals(refundId))) {
            throw new ApiException(ErrorCode.INVALID_STATE);
        }
        return operation;
    }
    private void reverse(String intent, String refundId, PaymentOperation operation, String status, boolean wasSettled) {
        String reason = "REFUND_" + status.toUpperCase(Locale.ROOT);
        var late = operation == null ? lateOperation(intent) : null;
        if (late != null) {
            if (wasSettled) {
                var amount = refundStates.forIntent(intent).stream().filter(state -> refundStates.refundId(state).equals(refundId)).findFirst().orElseThrow().amount();
                audit.refunded(entityType(late), late.targetId(), new Money(-amount.amountMinor(), amount.currency()), refundId, reason);
            }
            return;
        }
        if (operation != null && "REFUND_LATE".equals(operation.kind())) {
            if (operations.reverseRefund(operation.id(), refundId)) {
                audit.refunded(entityType(operation), operation.targetId(), new Money(-operation.amount().amountMinor(), operation.amount().currency()), refundId, reason);
            }
            return;
        }
        var collection = collections.byProviderReference(intent).orElse(null);
        if (collection != null) {
            var rows = collection.refunds() == null ? List.<Collection.Refund>of() : collection.refunds();
            var removed = rows.stream().filter(r -> r.providerRef().equals(refundId)).findFirst();
            if (removed.isPresent()) {
                collections.reverseRefund(collection.id(), refundId);
                long total = rows.stream().filter(r -> !r.providerRef().equals(refundId)).mapToLong(r -> r.amount().amountMinor()).sum();
                invoices.refunded(collection.invoiceId(), new Money(total, collection.amount().currency()), clock.instant());
                audit.refunded("Invoice", collection.invoiceId(), new Money(-removed.get().amount().amountMinor(), collection.amount().currency()), refundId, reason);
            }
            return;
        }
        for (var payment : upfront.forIntent(intent)) {
            var rows = payment.refunds() == null ? List.<UpfrontPayment.Refund>of() : payment.refunds();
            rows.stream().filter(r -> r.providerRef().equals(refundId)).findFirst().ifPresent(refund -> {
                upfront.reverseRefund(payment.id(), refundId);
                audit.refunded("UpfrontPayment", payment.id(), new Money(-refund.amount().amountMinor(), refund.amount().currency()), refundId, reason);
            });
        }
    }
    public boolean settled(String intent, String refundId, Money amount, Instant at, String stripeReason, String operationId) {
        refundStates.lockIntent(intent);
        // Metadata is durable before the external call; resultId may still be absent when Stripe delivers the webhook.
        var operation = operation(intent, refundId, amount, operationId);
        String reason = operation == null ? stripeReason : operation.reason();
        String actor = operation == null ? null : operation.actorId();
        var late = operation == null ? lateOperation(intent) : null;
        if (late != null) {
            if (!amount.currency().equals(late.amount().currency()) || amount.amountMinor() <= 0
                    || refundStates.forIntent(intent).stream().filter(state -> "succeeded".equals(state.status()))
                    .mapToLong(state -> state.amount().amountMinor()).sum() > late.amount().amountMinor()) { throw new ApiException(ErrorCode.REFUND_EXCEEDS_PAID); }
            audit.refunded(entityType(late), late.targetId(), amount, refundId, reason);
            return true;
        }
        if (operation != null && "REFUND_LATE".equals(operation.kind())) {
            boolean changed = operations.refundSettled(operation.id(), new UpfrontPayment.Refund(amount, refundId, at, reason, actor));
            if (changed) { audit.refunded(entityType(operation), operation.targetId(), amount, refundId, reason); }
            return changed;
        }
        var collection = collections.byProviderReference(intent).orElse(null);
        if (collection != null) {
            // Stripe may deliver a refund before payment_intent.succeeded. Keep the inbox item pending until that
            // success has settled the receipt; otherwise a full refund would prevent the later success from paying it.
            if (collection.status() != CollectionStatus.SUCCEEDED && collection.status() != CollectionStatus.REFUNDED) {
                throw new ApiException(ErrorCode.INVALID_STATE);
            }
            var refunds = collection.refunds() == null ? List.<Collection.Refund>of() : collection.refunds();
            if (refunds.stream().anyMatch(r -> r.providerRef().equals(refundId))) { return false; }
            long total = refunds.stream().mapToLong(r -> r.amount().amountMinor()).sum() + amount.amountMinor();
            if (!amount.currency().equals(collection.amount().currency()) || total > collection.amount().amountMinor()) { throw new ApiException(ErrorCode.REFUND_EXCEEDS_PAID); }
            collections.refund(collection.id(), new Collection.Refund(amount, refundId, at, reason, actor), total == collection.amount().amountMinor());
            invoices.refunded(collection.invoiceId(), new Money(total, amount.currency()), clock.instant());
            audit.refunded("Invoice", collection.invoiceId(), amount, refundId, reason); return true;
        }
        var payments = upfront.forIntent(intent);
        if (operation != null) {
            if (!"REFUND_UPFRONT".equals(operation.kind())) { throw new ApiException(ErrorCode.INVALID_STATE); }
        }
        if (payments.isEmpty()) { throw new ApiException(ErrorCode.INVALID_STATE); }
        if (amount.amountMinor() <= 0 || payments.stream().anyMatch(p -> !p.amountPaid().currency().equals(amount.currency()))) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH);
        }
        boolean changed = false;
        for (var payment : payments) {
            var refunds = payment.refunds() == null ? List.<UpfrontPayment.Refund>of() : payment.refunds();
            if (refunds.stream().anyMatch(r -> r.providerRef().equals(refundId))) { return false; }
        }
        var ledger = intentLedger(intent);
        var parts = ledger.settlementShares(amount.amountMinor(), operation == null ? null : operation.targetId(), operation == null ? null : operation.id());
        long previousOverpaid = ledger.overpaid();
        for (var payment : payments) {
            long paid = payment.amountPaid().amountMinor();
            long refunded = payment.refunds() == null ? 0 : payment.refunds().stream().mapToLong(r -> r.amount().amountMinor()).sum();
            long part = parts.getOrDefault(payment.id(), 0L);
            if (part > 0) {
                upfront.refund(payment.id(), new UpfrontPayment.Refund(new Money(part, amount.currency()), refundId, at, reason, actor), part + refunded == paid);
                audit.refunded("UpfrontPayment", payment.id(), new Money(part, amount.currency()), refundId, reason);
                changed = true;
            }
        }
        absorb(intent);
        reportOverpayment(intent, previousOverpaid);
        return changed;
    }
    private PaymentOperation lateOperation(String intent) {
        return operations.refundsForIntent(intent).stream().filter(op -> "REFUND_LATE".equals(op.kind())).findFirst().orElse(null);
    }
}
