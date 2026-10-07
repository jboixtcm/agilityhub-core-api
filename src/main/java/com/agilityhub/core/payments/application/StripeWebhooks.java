package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * R-12-21: durable receipt first, transactional handlers second. Refund lifecycle is carried by refund.*.
 * Modern charge.refunded events omit refunds: acknowledge them without a ledger mutation or provider fetch.
 * Embedded refunds from older API versions follow the same refund-id checkpoint for compatibility.
 */
@Service
public class StripeWebhooks {
    private final StripeInbox inbox; private final BillingTransactions tx; private final CardPayments cards; private final PaymentRefunds refunds;
    private final CheckoutService checkouts; private final SignupCheckoutRepository sessions; private final UpfrontPaymentRepository upfront;
    private final BillingCensusAccess census; private final BillingEvents events; private final Clock clock; private final ObjectMapper mapper;
    private final PaymentProviderRegistry provider;
    @org.springframework.beans.factory.annotation.Autowired private PaymentRetryPolicy retries;
    public StripeWebhooks(StripeInbox inbox, BillingTransactions tx, CardPayments cards, PaymentRefunds refunds, CheckoutService checkouts,
            SignupCheckoutRepository sessions, UpfrontPaymentRepository upfront, BillingCensusAccess census, BillingEvents events, Clock clock,
            ObjectMapper mapper, PaymentProviderRegistry provider) {
        this.inbox = inbox; this.tx = tx; this.cards = cards; this.refunds = refunds; this.checkouts = checkouts; this.sessions = sessions;
        this.upfront = upfront; this.census = census; this.events = events; this.clock = clock; this.mapper = mapper; this.provider = provider;
    }
    public void receive(String clubId, byte[] body) {
        var event = PaymentWebhookParser.parse(new String(body, StandardCharsets.UTF_8), null, null);
        try (var tenant = TenantContext.open(clubId)) {
            try {
                inbox.receive(new StripeEvent(event.id(), clubId, event.id(), event.type(), clock.instant(), null, null, hash(body)),
                        new Document("created", event.createdAt().getEpochSecond()).append("object", safe(event.object(), event.type().startsWith("refund."))));
            } catch (DuplicateKeyException duplicate) { return; }
            process(event.id());
        }
    }
    public void process(String id) {
        try {
            var row = inbox.findById(id).orElseThrow();
            if (row.processedAt() != null) { return; }
            var work = inbox.work(id);
            var object = mapper.valueToTree(work.get("object"));
            var at = Instant.ofEpochSecond(((Number) work.get("created")).longValue());
            // Card metadata may require a provider read (the webhook carries an id, not an expanded PaymentMethod).
            var card = ("setup_intent.succeeded".equals(row.type()) || ("checkout.session.completed".equals(row.type()) && "paid".equals(text(object, "payment_status"))))
                    ? provider.cardDetails(object) : null;
            tx.run(() -> {
                inbox.lock(id);
                if (inbox.findById(id).orElseThrow().processedAt() != null) { return null; }
                boolean unpaid = "checkout.session.completed".equals(row.type()) && !"paid".equals(text(object, "payment_status"));
                boolean changed = !unpaid && handle(row.type(), object, at, card);
                if (unpaid) { inbox.reason(id, "PAYMENT_NOT_PAID"); }
                String outcome = changed ? "PROCESSED" : "IGNORED";
                inbox.outcome(id, outcome, clock.instant());
                events.publish(BillingEvent.Kind.StripeWebhookReceived, id, Map.of("eventId", id, "type", row.type(), "outcome", outcome));
                return null;
            });
            try { refunds.executeLate(); }
            catch (RuntimeException unavailable) { /* The refund command is durable and its own worker will retry it. */ }
        } catch (RuntimeException deferred) {
            try { tx.run(() -> {
                var event = inbox.findById(id).orElseThrow();
                if (event.processedAt() != null) { return null; }
                if (inbox.failed(id, clock.instant(), retries.maxAttempts())) { retries.warn("Stripe event", id); }
                if (event.outcome() != StripeEventOutcome.FAILED) { events.publish(BillingEvent.Kind.StripeWebhookReceived, id,
                        Map.of("eventId", id, "type", event.type(), "outcome", "FAILED")); }
                return null;
            }); }
            catch (RuntimeException unavailable) { /* Receipt is durable; pending() also retries rows without an outcome. */ }
        }
    }
    private boolean handle(String type, JsonNode object, Instant at, BillingCensusAccess.Card card) {
        String id = object.path("id").asText(); var metadata = object.path("metadata");
        return switch (type) {
            case "payment_intent.succeeded", "payment_intent.payment_failed" -> {
                boolean success = type.endsWith("succeeded");
                String code = object.path("last_payment_error").path("decline_code").asText(
                        object.path("last_payment_error").path("code").asText("card_declined"));
                if (text(metadata, "operationId") != null && text(metadata, "invoiceId") == null) { yield false; } // Checkout settles its own rows.
                boolean changed = cards.settle(id, text(metadata, "collectionId"), success, code, at);
                if (changed && !success && "expired_card".equals(code)) { invalidate(text(object, "customer"), text(object, "payment_method")); }
                yield changed;
            }
            case "checkout.session.completed" -> {
                String operation = text(metadata, "operationId");
                var session = sessions.findById(operation == null ? id : operation).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
                if ("setup".equals(session.mode())) { yield false; } // SetupIntent owns card replacement, independent of Checkout event order.
                String intent = text(object, "payment_intent");
                checkouts.completeWebhook(session.id(), intent, card, at, object.path("amount_total").asLong());
                yield true;
            }
            case "checkout.session.expired" -> {
                String operation = text(metadata, "operationId"); checkouts.expire(operation == null ? id : operation); yield true;
            }
            case "setup_intent.succeeded" -> {
                String member = text(metadata, "memberId"), operation = text(metadata, "operationId");
                var session = sessions.findById(operation).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
                if (!session.memberId().equals(member) || card == null) { throw new ApiException(ErrorCode.INVALID_STATE); }
                yield checkouts.completeSetup(session, card, at);
            }
            case "payment_method.detached" -> invalidate(null, id);
            case "customer.deleted" -> invalidate(id, null);
            case "refund.created", "refund.updated", "refund.failed" -> refunds.reconcile(text(object, "payment_intent"), id,
                    new Money(object.path("amount").asLong(), object.path("currency").asText().toUpperCase(Locale.ROOT)),
                    object.path("status").asText(), at, object.path("reason").asText("requested_by_customer"), text(metadata, "operationId"));
            case "charge.refunded" -> {
                boolean changed = object.path("refunds").path("data").isEmpty();
                for (var refund : object.path("refunds").path("data")) {
                    changed |= refunds.reconcile(text(object, "payment_intent"), refund.path("id").asText(),
                            new Money(refund.path("amount").asLong(), object.path("currency").asText().toUpperCase(Locale.ROOT)),
                            refund.path("status").asText("succeeded"), at, refund.path("reason").asText("requested_by_customer"), text(refund.path("metadata"), "operationId"));
                }
                yield changed;
            }
            default -> false;
        };
    }
    private boolean invalidate(String customer, String method) {
        var changed = census.invalidateCards(customer, method);
        for (String member : changed) { events.publish(BillingEvent.Kind.MemberCardInvalidated, member, Map.of("memberId", member, "reason", "NO_PAYMENT_METHOD")); }
        return !changed.isEmpty();
    }
    static String text(JsonNode node, String key) {
        var value = node.path(key); return value.isTextual() ? value.asText() : value.isObject() ? value.path("id").asText(null) : null;
    }
    private static Document safe(JsonNode object, boolean refund) {
        var result = new Document();
        for (String key : List.of("id", "status", "reason", "payment_status", "amount_total", "amount", "currency", "customer", "payment_method", "payment_intent", "setup_intent", "setup_future_usage")) {
            var value = object.path(key);
            if (value.isTextual()) { result.put(key, value.asText()); }
            else if (value.isNumber()) { result.put(key, value.asLong()); }
            else if (value.isObject()) { result.put(key, text(object, key)); }
        }
        var metadata = new Document();
        for (String key : refund ? List.of("operationId", "reason") : List.of("clubId", "memberId", "invoiceId", "collectionId", "operationId")) {
            if (object.path("metadata").path(key).isTextual()) { metadata.put(key, object.path("metadata").path(key).asText()); }
        }
        result.put("metadata", metadata);
        var error = new Document();
        for (String key : List.of("code", "decline_code")) { if (object.path("last_payment_error").path(key).isTextual()) { error.put(key, object.path("last_payment_error").path(key).asText()); } }
        result.put("last_payment_error", error);
        var refunds = new ArrayList<Document>();
        for (var refundRow : object.path("refunds").path("data")) {
            var refundMetadata = new Document();
            for (String key : List.of("operationId", "reason")) {
                if (refundRow.path("metadata").path(key).isTextual()) { refundMetadata.put(key, refundRow.path("metadata").path(key).asText()); }
            }
            refunds.add(new Document("id", refundRow.path("id").asText()).append("amount", refundRow.path("amount").asLong())
                    .append("status", refundRow.path("status").asText("succeeded")).append("reason", refundRow.path("reason").asText("requested_by_customer"))
                    .append("metadata", refundMetadata));
        }
        result.put("refunds", new Document("data", refunds)); return result;
    }
    private static String hash(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
