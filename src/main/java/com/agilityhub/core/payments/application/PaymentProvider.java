package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * A14: Stripe is supplied in E8; this port describes the payment/setup checkout handoff.
 * <p>
 * Timeouts (CONVENCIONS_API §7, ruling E80): a keyed `POST /checkout-sessions` calls the provider while it holds its
 * Idempotency-Key's claim, and a retry takes that claim over once `IdempotencyRepository.CLAIM_LEASE` (10 min) has passed.
 * So every call to the provider has a timeout well below that lease: {@link #callTimeout()}, at most {@link #MAX_CALL_TIMEOUT}
 * for the whole call (connecting, waiting for the answer and the client's own retries). One keyed checkout makes at most two
 * calls (the session, then its expiry after a failure), so the provider takes at most a tenth of the lease and leaves the rest
 * to the request's Mongo transactions (each attempt ends within the server's 60-second transaction lifetime): only a request
 * whose process stopped leaves its claim to the retry.
 */
public interface PaymentProvider {
    enum Capability { CHECKOUT, OFF_SESSION, REFUND, CARD_SETUP, FORGET_CUSTOMER }
    record OffSessionRequest(Money amount, String customerId, String paymentMethodId, String idempotencyKey, Map<String,String> metadata) { }
    record OffSessionResult(String paymentIntentId, String status, String failureCode) { }
    record RefundResult(String id, String status) { }
    record WebhookEvent(String id, String type, Instant createdAt, com.fasterxml.jackson.databind.JsonNode object) { }
    default boolean supports(Capability capability) { return false; }
    default OffSessionResult createOffSessionPayment(OffSessionRequest request) { throw disabled(); }
    default RefundResult refund(String chargeId, Money amount, String idempotencyKey, String reason) { throw disabled(); }
    default WebhookEvent parseWebhook(String payload, String signatureHeader, String webhookSecret) { throw disabled(); }
    default com.agilityhub.core.shared.application.BillingCensusAccess.Card cardDetails(com.fasterxml.jackson.databind.JsonNode object) { return null; }
    default void forgetCustomer(String customerId) { throw disabled(); }
    private static com.agilityhub.core.shared.domain.ApiException disabled() {
        return new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED);
    }
    /** The longest a single provider call may take, retries included (ruling E80): a small fraction of the 10-minute claim lease. */
    Duration MAX_CALL_TIMEOUT = Duration.ofSeconds(30);
    record Item(String paymentId,String description,Money amount) { }
    record Request(String sessionId,String clubId,String memberId,String mode,List<Item> lines,String customerEmail,
            String clientReferenceId,Map<String,Object> metadata,String setupFutureUsage,String successUrl,String cancelUrl,Instant expiresAt) { }
    /**
     * Opens the provider session for {@code request} and returns its URL. Idempotent by {@code Request.sessionId} (the
     * provider's idempotency key, E5-T28): asked again for the same id, it answers the same session and opens no second one
     * (a `POST /checkout-sessions` retried after its answer was lost, CONVENCIONS_API §7). It gives up after
     * {@link #callTimeout()}, as every call of this port does.
     */
    String createCheckoutSession(Request request);
    void complete(String sessionId);
    void expire(String sessionId);
    /** The timeout this implementation's client applies to each call, retries included: positive and at most {@link #MAX_CALL_TIMEOUT}. */
    Duration callTimeout();
    /** {@code provider}, when its {@link #callTimeout()} keeps the contract; otherwise it never serves a request (checked at startup). */
    static PaymentProvider requireTimeout(PaymentProvider provider) {
        var timeout = provider.callTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_CALL_TIMEOUT) > 0) {
            throw new IllegalStateException("Payment provider " + provider.getClass().getSimpleName() + " has call timeout " + timeout
                    + "; the contract needs a positive one of at most " + MAX_CALL_TIMEOUT);
        }
        return provider;
    }
}
