package com.agilityhub.core.payments.application.stripe;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.net.RequestOptions;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** ADR-009: requests use only the open club's own account; no global SDK key and no Stripe Connect account header. */
@Component
public class StripePaymentProvider implements PaymentProvider {
    @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
    private final ClubPaymentProviders settings;
    private final ProviderSecretVault vault;
    private final StripeCalls calls;
    private final SignupCheckoutRepository sessions;
    public StripePaymentProvider(ClubPaymentProviders settings, ProviderSecretVault vault, StripeCalls calls, SignupCheckoutRepository sessions) {
        this.settings = settings; this.vault = vault; this.calls = calls; this.sessions = sessions;
    }
    private StripeClient client() {
        String club = TenantContext.require();
        var config = settings.stripe(club).orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
        if (!config.enabled()) { throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }
        String key;
        try { key = vault.decrypt(config.secretKeyEnc(), club, "STRIPE", "secretKeyEnc"); }
        catch (RuntimeException invalid) { throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID); }
        if (!Set.of("test", "live").contains(Objects.toString(config.mode(), "")) || !key.startsWith("sk_" + config.mode() + "_")) {
            throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID);
        }
        return newClient(key);
    }
    StripeClient newClient(String key) {
        return StripeClient.builder().setApiKey(key).setConnectTimeout(1000).setReadTimeout(3000).setMaxNetworkRetries(0).build();
    }
    private RequestOptions options(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).setConnectTimeout(1000).setReadTimeout(3000).setMaxNetworkRetries(0).build();
    }
    @Override public boolean supports(Capability capability) { return true; }
    @Override public Duration callTimeout() { return MAX_CALL_TIMEOUT; }
    @Override public String createCheckoutSession(Request request) {
        if (!TenantContext.require().equals(request.clubId())) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
        var client = client();
        var metadata = new LinkedHashMap<String, String>();
        request.metadata().forEach((key, value) -> metadata.put(key, value instanceof List<?> list ? String.join(",", list.stream().map(Object::toString).toList()) : value.toString()));
        metadata.put("operationId", request.sessionId());
        var params = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.valueOf(request.mode().toUpperCase(Locale.ROOT)))
                .setSuccessUrl(request.successUrl()).setCancelUrl(request.cancelUrl()).setClientReferenceId(request.clientReferenceId())
                .putAllMetadata(metadata); // Stripe defaults to 24h; Core enforces its shorter booking deadline and refunds late captures.
        if (!"setup".equals(request.mode()) && request.customerEmail() != null) { params.setCustomerEmail(request.customerEmail()); }
        if ("setup".equals(request.mode())) {
            var customer = calls.call(() -> client.v1().customers().create(CustomerCreateParams.builder().putMetadata("memberId", request.memberId()).build(), options("customer:" + request.memberId())));
            params.setCustomer(customer.getId());
            params.setCurrency(request.metadata().getOrDefault("currency", "EUR").toString().toLowerCase(Locale.ROOT)).addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                    .setSetupIntentData(SessionCreateParams.SetupIntentData.builder().putAllMetadata(metadata).build());
        } else {
            var payment = SessionCreateParams.PaymentIntentData.builder().putAllMetadata(metadata);
            if (request.setupFutureUsage() != null) {
                params.setCustomerCreation(SessionCreateParams.CustomerCreation.ALWAYS);
                payment.setSetupFutureUsage(SessionCreateParams.PaymentIntentData.SetupFutureUsage.OFF_SESSION);
            }
            params.setPaymentIntentData(payment.build());
            for (Item item : request.lines()) {
                params.addLineItem(SessionCreateParams.LineItem.builder().setQuantity(1L).setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(item.amount().currency().toLowerCase(Locale.ROOT)).setUnitAmount(item.amount().amountMinor())
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder().setName(item.description()).build()).build()).build());
            }
        }
        var session = calls.call(() -> client.v1().checkout().sessions().create(params.build(), options(request.sessionId())));
        sessions.providerSession(request.sessionId(), session.getId());
        return session.getUrl();
    }
    @Override public OffSessionResult createOffSessionPayment(OffSessionRequest request) {
        var client = client();
        var params = PaymentIntentCreateParams.builder().setAmount(request.amount().amountMinor())
                .setCurrency(request.amount().currency().toLowerCase(Locale.ROOT)).setCustomer(request.customerId()).setPaymentMethod(request.paymentMethodId())
                .setConfirm(true).setOffSession(true).putAllMetadata(request.metadata()).build();
        return calls.call(() -> {
            try {
                var intent = client.v1().paymentIntents().create(params, options(request.idempotencyKey()));
                return new OffSessionResult(intent.getId(), intent.getStatus(), null);
            } catch (com.stripe.exception.CardException rejected) {
                var error = rejected.getStripeError();
                if (error == null || error.getPaymentIntent() == null) { throw rejected; }
                return new OffSessionResult(error.getPaymentIntent().getId(), "failed", Objects.toString(error.getDeclineCode(), error.getCode()));
            }
        });
    }
    @Override public RefundResult refund(String chargeId, Money amount, String idempotencyKey, String reason) {
        var client = client();
        var builder = RefundCreateParams.builder().setAmount(amount.amountMinor()).putMetadata("reason", reason);
        if (chargeId.startsWith("pi_")) { builder.setPaymentIntent(chargeId); } else { builder.setCharge(chargeId); }
        var refund = calls.call(() -> client.v1().refunds().create(builder.build(), options(idempotencyKey)));
        return new RefundResult(refund.getId(), refund.getStatus());
    }
    @Override public WebhookEvent parseWebhook(String payload, String signatureHeader, String webhookSecret) {
        return PaymentWebhookParser.authenticate(payload, signatureHeader, webhookSecret, clock);
    }
    @Override public com.agilityhub.core.shared.application.BillingCensusAccess.Card cardDetails(com.fasterxml.jackson.databind.JsonNode object) {
        String method = object.path("payment_method").asText(null), customer = object.path("customer").asText(null);
        var client = client();
        if (method == null && object.path("payment_intent").isTextual()) {
            var intent = calls.call(() -> client.v1().paymentIntents().retrieve(object.path("payment_intent").asText(), options(null)));
            if (!"off_session".equals(intent.getSetupFutureUsage())) { return null; }
            method = intent.getPaymentMethod(); customer = intent.getCustomer();
        }
        if (method == null) { return null; }
        String methodId = method;
        var payment = calls.call(() -> client.v1().paymentMethods().retrieve(methodId, options(null)));
        if (payment.getCard() == null) { throw new ApiException(ErrorCode.NO_PAYMENT_METHOD); }
        return new com.agilityhub.core.shared.application.BillingCensusAccess.Card(customer, method, payment.getCard().getLast4(), payment.getCard().getBrand(), false);
    }
    @Override public void forgetCustomer(String customerId) {
        var client = client(); calls.call(() -> {
            try { return client.v1().customers().delete(customerId, options("forget:" + customerId)); }
            catch (com.stripe.exception.InvalidRequestException missing) {
                if (Integer.valueOf(404).equals(missing.getStatusCode())) { return null; }
                throw missing;
            }
        });
    }
    @Override public void complete(String sessionId) { throw new ApiException(ErrorCode.INVALID_STATE); }
    @Override public void expire(String sessionId) {
        var reference = sessions.providerSession(sessionId);
        if (reference.isEmpty()) { return; }
        var client = client(); calls.call(() -> client.v1().checkout().sessions().expire(reference.get(), options("expire:" + sessionId)));
    }
}
