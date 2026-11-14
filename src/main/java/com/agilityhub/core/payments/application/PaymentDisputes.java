package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.PaymentDispute;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * PAY-06 (owner decision of 13 Nov 2026, S12 R-12-21): a Stripe dispute (chargeback) flags the card payment it disputes and tells
 * the club's admins. `charge.dispute.created`, `.updated` and `.closed` find the payment by the dispute's PaymentIntent — the
 * up-front payments a Checkout settled (every row of that checkout) or the card collection of an invoice — and keep the dispute
 * on it ({@link PaymentDispute}: Stripe's id, status, reason, amount, when Core learnt of it and when Stripe closed it). The first event of a
 * dispute publishes `PaymentDisputed` (N-56 to the admins); the later ones only bring the flag up to date, and an older event
 * delivered late never reopens a closed dispute. Nothing else changes: the payment stays as it is (`PAID`, `SUCCEEDED`), and no
 * pack, booking or invoice is touched. Runs in the webhook's transaction.
 */
@Service
public class PaymentDisputes {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PaymentDisputes.class);
    private final UpfrontPaymentRepository upfront; private final CollectionRepository collections; private final InvoiceRepository invoices;
    private final BillingEvents events;
    public PaymentDisputes(UpfrontPaymentRepository upfront, CollectionRepository collections, InvoiceRepository invoices, BillingEvents events) {
        this.upfront = upfront; this.collections = collections; this.invoices = invoices; this.events = events;
    }

    /**
     * The dispute {@code object} of a `charge.dispute.*` event created at {@code at}: whether it changed a payment of the club. A
     * dispute whose PaymentIntent no payment of Core has (a payment taken outside the app, a late checkout completion that settled
     * no row) changes nothing and is logged; Stripe's own e-mail and dashboard remain the treasurer's source for it.
     */
    public boolean record(JsonNode object, Instant at) {
        String id = text(object, "id"), intent = text(object, "payment_intent"), status = text(object, "status");
        if (id == null || intent == null || status == null) { return false; }
        var rows = upfront.forIntent(intent);
        var collection = rows.isEmpty() ? collections.byProviderReference(intent).filter(c -> c.provider() == CollectionProvider.STRIPE)
                : Optional.<Collection>empty();
        if (rows.isEmpty() && collection.isEmpty()) {
            LOG.warn("Stripe dispute for a payment Core does not hold: disputeId={} clubId={}", id, TenantContext.require());
            return false;
        }
        var previous = rows.isEmpty() ? collection.get().dispute() : rows.getFirst().dispute();
        boolean known = previous != null && id.equals(previous.providerRef());
        if (known && previous.closed() && !PaymentDispute.CLOSED.contains(status)) { return false; }
        var amount = new Money(object.path("amount").asLong(), object.path("currency").asText("").toUpperCase(Locale.ROOT));
        var closedAt = PaymentDispute.CLOSED.contains(status) ? (known && previous.closedAt() != null ? previous.closedAt() : at) : null;
        var dispute = new PaymentDispute(id, status, text(object, "reason"), amount, known ? previous.openedAt() : at, closedAt);
        if (dispute.equals(previous)) { return false; }
        String memberId; String invoiceId = null; List<String> paymentIds = List.of();
        if (!rows.isEmpty()) {
            paymentIds = rows.stream().map(UpfrontPayment::id).toList();
            upfront.dispute(paymentIds, dispute);
            memberId = rows.getFirst().memberId();
        } else {
            collections.dispute(collection.get().id(), dispute);
            invoiceId = collection.get().invoiceId();
            memberId = invoices.findById(invoiceId).map(Invoice::memberId).orElse(null);
        }
        if (!known) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("disputeId", id); payload.put("memberId", memberId); payload.put("amount", amount);
            payload.put("reason", Objects.toString(dispute.reason(), "")); payload.put("upfrontPaymentIds", paymentIds); payload.put("invoiceId", invoiceId);
            events.publish(BillingEvent.Kind.PaymentDisputed, memberId, payload);
        }
        return true;
    }

    private static String text(JsonNode node, String key) { var value = node.path(key); return value.isTextual() ? value.asText() : null; }
}
