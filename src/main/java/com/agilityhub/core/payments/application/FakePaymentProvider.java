package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** E8 deterministic provider; keeps the E3 FakeCheckoutGateway API and signup smoke compatible. */
@Component @Profile("(local | test) & !staging & !prod")
public class FakePaymentProvider extends FakeCheckoutGateway {
    @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
    public record Call(String clubId, String operation, String key, Object request) { }
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<StripeWebhooks> webhooks;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> results = new ConcurrentHashMap<>();
    private final Map<String, java.time.Instant> moneyCreatedAt = new ConcurrentHashMap<>();
    private final Queue<String> outcomes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public FakePaymentProvider(ObjectProvider<CheckoutService> checkout) { super(checkout); }
    public void deliverWebhook(String type, Map<String, Object> payload) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            webhooks.getObject().receive(TenantContext.require(), mapper.writeValueAsBytes(Map.of("id", payload.get("eventId"), "type", type,
                    "created", payload.get("created"), "data", Map.of("object", payload.get("object")))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException(invalid); }
    }
    public void succeed() { outcomes.add("succeeded"); }
    public void fail(String code) { outcomes.add("failed:" + code); }
    public void requireAction() { outcomes.add("requires_action"); }
    public List<Call> calls() { synchronized (calls) { return List.copyOf(calls); } }
    private java.util.function.BiConsumer<Call, RefundResult> beforeRefundReturn = (call, result) -> {};
    private java.util.function.Consumer<OffSessionResult> afterCharge = result -> {};
    public void afterCharge(java.util.function.Consumer<OffSessionResult> callback) { afterCharge = callback; }
    private String refundStatus = "succeeded";
    public void refundStatus(String status) { refundStatus = status; }
    public void beforeRefundReturn(java.util.function.BiConsumer<Call, RefundResult> callback) { beforeRefundReturn = callback; }
    private final Queue<com.agilityhub.core.shared.domain.ErrorCode> refundRejections = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** The next refund call is definitively rejected before any money moves, as Stripe's 4xx answers are. */
    public void rejectRefund(com.agilityhub.core.shared.domain.ErrorCode code) { refundRejections.add(code); }
    public void reset() { calls.clear(); results.clear(); moneyCreatedAt.clear(); afterCharge = result -> {}; outcomes.clear(); cards.clear(); beforeRefundReturn = (call, result) -> {}; refundStatus = "succeeded"; refundRejections.clear(); }
    /** Model Stripe pruning keys after 24 hours, including a lost response after the remote effect. */
    private Object moneyResult(String key, java.util.function.Supplier<Object> create) {
        return results.compute(key, (ignored, previous) -> {
            if (previous != null && clock.instant().isBefore(moneyCreatedAt.get(key).plus(java.time.Duration.ofHours(24)))) { return previous; }
            var result = create.get(); moneyCreatedAt.put(key, clock.instant()); return result;
        });
    }
    @Override public boolean supports(Capability capability) { return true; }
    @Override public String createCheckoutSession(Request request) {
        String url = super.createCheckoutSession(request);
        results.computeIfAbsent(TenantContext.require() + ":checkout:" + request.sessionId(), ignored -> {
            calls.add(new Call(TenantContext.require(), "checkout", request.sessionId(), request)); return url;
        });
        return url;
    }
    @Override public OffSessionResult createOffSessionPayment(OffSessionRequest request) {
        String club = TenantContext.require(), key = club + ":charge:" + request.idempotencyKey();
        var result = (OffSessionResult) moneyResult(key, () -> {
            calls.add(new Call(club, "charge", request.idempotencyKey(), request));
            String outcome = outcomes.poll(); if (outcome == null) { outcome = "succeeded"; }
            return new OffSessionResult("pi_fake_" + UUID.randomUUID(),
                    outcome.startsWith("failed:") ? "failed" : outcome, outcome.startsWith("failed:") ? outcome.substring(7) : null);
        });
        afterCharge.accept(result);
        return result;
    }
    @Override public RefundResult refund(String chargeId, Money amount, String idempotencyKey, String reason) {
        return refund(chargeId, amount, idempotencyKey, reason, null);
    }
    @Override public RefundResult refund(String chargeId, Money amount, String idempotencyKey, String reason, String operationId) {
        String club = TenantContext.require(), key = club + ":refund:" + idempotencyKey;
        var rejection = results.containsKey(key) ? null : refundRejections.poll(); // Stripe replays a key that already moved money.
        if (rejection != null) { throw new PaymentNotSubmitted(rejection); }
        var request = new LinkedHashMap<String, Object>(Map.of("chargeId", chargeId, "amount", amount, "reason", reason));
        if (operationId != null) { request.put("operationId", operationId); }
        var call = new Call(club, "refund", idempotencyKey, request);
        var result = (RefundResult) moneyResult(key, () -> {
            calls.add(call);
            return new RefundResult("re_fake_" + UUID.randomUUID(), refundStatus);
        });
        beforeRefundReturn.accept(call, result);
        return result;
    }
    @Override public WebhookEvent parseWebhook(String payload, String signature, String secret) { return PaymentWebhookParser.authenticate(payload, signature, secret, clock); }
    private final Map<String, com.agilityhub.core.shared.application.BillingCensusAccess.Card> cards = new ConcurrentHashMap<>();
    public void card(String method, com.agilityhub.core.shared.application.BillingCensusAccess.Card card) { cards.put(method, card); }
    @Override public com.agilityhub.core.shared.application.BillingCensusAccess.Card cardDetails(com.fasterxml.jackson.databind.JsonNode object) {
        return cards.get(object.path("payment_method").asText(object.path("payment_intent").asText()));
    }
    @Override public void forgetCustomer(String customerId) {
        results.computeIfAbsent(TenantContext.require() + ":forget:" + customerId, ignored -> {
            calls.add(new Call(TenantContext.require(), "forget", customerId, Map.of())); return Boolean.TRUE;
        });
    }
}
