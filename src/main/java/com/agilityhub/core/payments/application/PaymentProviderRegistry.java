package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.stripe.StripePaymentProvider;
import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Resolves on every call, so a disabled or rotated club configuration applies immediately. */
@Component @Primary
public class PaymentProviderRegistry implements PaymentProvider {
    private final ClubPaymentProviders clubs;
    private final ObjectProvider<FakePaymentProvider> fake;
    private final StripePaymentProvider stripe;
    public PaymentProviderRegistry(ClubPaymentProviders clubs, ObjectProvider<FakePaymentProvider> fake, StripePaymentProvider stripe) {
        this.clubs = clubs; this.fake = fake; this.stripe = stripe;
    }
    public PaymentProvider resolve() {
        if (!clubs.stripe(TenantContext.require()).map(config -> config.enabled()).orElse(false)) { return DISABLED; }
        return implementation();
    }
    private PaymentProvider implementation() {
        return fake.getIfAvailable(() -> null) == null ? stripe : fake.getObject();
    }
    public void require(Capability capability) {
        if (!supports(capability)) { throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }
    }
    @Override public boolean supports(Capability capability) { return resolve().supports(capability); }
    @Override public String createCheckoutSession(Request request) { return resolve().createCheckoutSession(request); }
    @Override public OffSessionResult createOffSessionPayment(OffSessionRequest request) { return resolve().createOffSessionPayment(request); }
    @Override public RefundResult refund(String id, Money amount, String key, String reason) { return resolve().refund(id, amount, key, reason); }
    @Override public WebhookEvent parseWebhook(String payload, String signature, String secret) { return resolve().parseWebhook(payload, signature, secret); }
    @Override public com.agilityhub.core.shared.application.BillingCensusAccess.Card cardDetails(com.fasterxml.jackson.databind.JsonNode object) { return resolve().cardDetails(object); }
    @Override public void forgetCustomer(String customerId) { resolve().forgetCustomer(customerId); }
    @Override public void complete(String id) { resolve().complete(id); }
    // Disabling new payments must not prevent closing a session opened by an already running request (S04 R-04-26).
    @Override public void expire(String id) { implementation().expire(id); }
    @Override public Duration callTimeout() { return MAX_CALL_TIMEOUT; }
    private static final PaymentProvider DISABLED = new PaymentProvider() {
        private ApiException error() { return new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }
        public String createCheckoutSession(Request request) { throw error(); }
        public void complete(String id) { throw error(); }
        public void expire(String id) { throw error(); }
        public Duration callTimeout() { return MAX_CALL_TIMEOUT; }
    };
}
