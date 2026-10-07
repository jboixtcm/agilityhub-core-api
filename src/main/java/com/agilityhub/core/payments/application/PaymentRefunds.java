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
    public record Accepted(String id, Money amount, String providerRef) { }
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
            upfront.lock(id);
            var refunds = payment.refunds() == null ? List.<UpfrontPayment.Refund>of() : payment.refunds();
            return reserve("REFUND_UPFRONT", id, payment.stripe().paymentIntentId(), upfront.captured(id).orElse(payment.amountPaid()), amount, reason, key,
                    refunds.stream().map(UpfrontPayment.Refund::providerRef).toList(), refunds.stream().mapToLong(r -> r.amount().amountMinor()).sum());
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
        String key = "late:" + reference;
        if (operations.byKey(key).isPresent()) { return; }
        operations.insert(new PaymentOperation(UUID.randomUUID().toString(), TenantContext.require(), "REFUND_LATE", sessionId, reference,
                amount, key, "LATE_COMPLETION", null, null, clock.instant(), null));
    }
    /** Both cancellation consumers serialize on the payment before deciding refund versus credit. */
    public void compensate(String paymentId, String policy, boolean beforeConfirmation) {
        tx.run(() -> {
            upfront.lock(paymentId);
            var payment = upfront.findById(paymentId).orElseThrow();
            if (compensated(payment)) { return null; }
            if (beforeConfirmation || "REFUND".equals(policy)) {
                upfront(paymentId, null, beforeConfirmation ? "LATE_COMPLETION" : "BOOKING_CANCELLED", "refund:" + paymentId);
            } else if ("CREDIT".equals(policy)) {
                Money captured = upfront.captured(paymentId).orElse(payment.amountPaid());
                charges.insert(new PendingCharge(UUID.randomUUID().toString(), TenantContext.require(), payment.memberId(), payment.dogId(), payment.bookingId(), null,
                        new Money(-captured.amountMinor(), captured.currency()), payment.concept(), clock.instant(), null, null));
            }
            return null;
        });
    }
    private boolean compensated(UpfrontPayment payment) {
        return operations.forTarget(payment.id()).stream().anyMatch(op -> op.kind().startsWith("REFUND"))
                || payment.bookingId() != null && charges.forBooking(payment.bookingId()).isPresent();
    }
    /** A booking has one upfront capture: the late path uses the same payment key as BookingCancelled. */
    public void lateBooking(String paymentId, String reference, Money amount) {
        if (reference == null || amount.amountMinor() <= 0) { return; }
        tx.run(() -> {
            upfront.lock(paymentId);
            var payment = upfront.findById(paymentId).orElseThrow();
            if (compensated(payment) || operations.byKey("late:" + reference).isPresent()) { return null; }
            if ("PAID".equals(payment.status()) && payment.stripe() != null && reference.equals(payment.stripe().paymentIntentId())) {
                upfront(paymentId, amount, "LATE_COMPLETION", "refund:" + paymentId);
            } else {
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
        retries.execute(op, () -> {
            var result = provider.refund(op.providerRef(), op.amount(), op.key(), op.reason(), op.id());
            tx.run(() -> {
                if (operations.findById(id).orElseThrow().resultId() == null) {
                    operations.completed(id, result.id());
                    operations.providerStatus(id, result.status());
                    audit.refunded(entityType(op), op.targetId(), op.amount(), result.id(), op.reason());
                }
                return null;
            });
        });
    }
    /** Refund objects are authoritative. A terminal failure wins over a redelivered older success. */
    public boolean reconcile(String intent, String refundId, Money amount, String status, Instant at, String reason, String operationId) {
        var previous = refundStates.lock(refundId);
        if (previous.status() != null) {
            if (!Objects.equals(previous.intent(), intent) || !previous.amount().equals(amount)) { throw new ApiException(ErrorCode.INVALID_STATE); }
            if (Set.of("failed", "canceled").contains(previous.status()) || previous.status().equals(status)
                    || "succeeded".equals(previous.status()) && !Set.of("failed", "canceled").contains(status)) { return false; }
        }
        var operation = operation(intent, refundId, amount, operationId);
        if ("succeeded".equals(status)) { settled(intent, refundId, amount, at, reason, operationId); }
        else if (Set.of("failed", "canceled").contains(status)) {
            reverse(intent, refundId, operation, status);
            retries.warnRefund(refundId, status);
        }
        refundStates.outcome(refundId, intent, amount, status, at);
        if (operation != null) { operations.refundStatus(operation.id(), status); }
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
                || !operation.amount().equals(amount))) { throw new ApiException(ErrorCode.INVALID_STATE); }
        return operation;
    }
    private void reverse(String intent, String refundId, PaymentOperation operation, String status) {
        String reason = "REFUND_" + status.toUpperCase(Locale.ROOT);
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
        // Metadata is durable before the external call; resultId may still be absent when Stripe delivers the webhook.
        var operation = operation(intent, refundId, amount, operationId);
        String reason = operation == null ? stripeReason : operation.reason();
        String actor = operation == null ? null : operation.actorId();
        if (operation != null && "REFUND_LATE".equals(operation.kind())) {
            return operations.refundSettled(operation.id(), new UpfrontPayment.Refund(amount, refundId, at, reason, actor));
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
            invoices.refunded(collection.invoiceId(), new Money(total, amount.currency()), clock.instant()); return true;
        }
        var payments = upfront.forIntent(intent);
        if (operation != null) {
            if (!"REFUND_UPFRONT".equals(operation.kind())) { throw new ApiException(ErrorCode.INVALID_STATE); }
            payments = payments.stream().filter(p -> p.id().equals(operation.targetId())).toList();
        }
        if (payments.isEmpty()) { throw new ApiException(ErrorCode.INVALID_STATE); }
        if (amount.amountMinor() <= 0 || payments.stream().anyMatch(p -> !p.amountPaid().currency().equals(amount.currency()))) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH);
        }
        long remaining = amount.amountMinor(); boolean changed = false;
        for (var payment : payments) {
            var refunds = payment.refunds() == null ? List.<UpfrontPayment.Refund>of() : payment.refunds();
            if (refunds.stream().anyMatch(r -> r.providerRef().equals(refundId))) { return false; }
        }
        for (var payment : payments) {
            long paid = payment.amountPaid().amountMinor();
            long refunded = payment.refunds() == null ? 0 : payment.refunds().stream().mapToLong(r -> r.amount().amountMinor()).sum();
            long part = Math.min(remaining, paid - refunded);
            if (part > 0) {
                upfront.refund(payment.id(), new UpfrontPayment.Refund(new Money(part, amount.currency()), refundId, at, reason, actor), part + refunded == paid);
                remaining -= part; changed = true;
            }
        }
        if (remaining > 0) { throw new ApiException(ErrorCode.REFUND_EXCEEDS_PAID); }
        return changed;
    }
}
