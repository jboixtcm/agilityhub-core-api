package com.agilityhub.core.payments.application.stripe;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.HostedDemoPolicy;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.net.RequestOptions;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** ADR-009: requests use only the open club's own account; no global SDK key and no Stripe Connect account header. */
@Component
public class StripePaymentProvider implements PaymentProvider {
    @org.springframework.beans.factory.annotation.Value("${core.hosted-demo.enabled:false}") private boolean hostedDemo;
    @org.springframework.beans.factory.annotation.Value("${core.payments.stripe-live-enabled-club-ids:}") private String stripeLiveEnabledClubIds = "";
    @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
    private final ClubPaymentProviders settings;
    private final ProviderSecretVault vault;
    private final StripeCalls calls;
    private final SignupCheckoutRepository sessions;
    public StripePaymentProvider(ClubPaymentProviders settings, ProviderSecretVault vault, StripeCalls calls, SignupCheckoutRepository sessions) {
        this.settings = settings; this.vault = vault; this.calls = calls; this.sessions = sessions;
    }
    private StripeClient client() { return client(true); }
    private StripeClient client(boolean requireEnabled) {
        String club = TenantContext.require();
        var config = settings.stripe(club).orElseThrow(() -> new PaymentNotSubmitted(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
        if (requireEnabled && !config.enabled()) { throw new PaymentNotSubmitted(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }
        if (!HostedDemoPolicy.permitsStripeMode(hostedDemo,stripeLiveEnabledClubIds,club,config.mode())) { throw new PaymentNotSubmitted(ErrorCode.PROVIDER_CONFIG_INVALID); }
        String key;
        try { key = vault.decrypt(config.secretKeyEnc(), club, "STRIPE", "secretKeyEnc"); }
        catch (RuntimeException invalid) { throw new PaymentNotSubmitted(ErrorCode.PROVIDER_CONFIG_INVALID); }
        if (!Set.of("test", "live").contains(Objects.toString(config.mode(), "")) || !key.startsWith("sk_" + config.mode() + "_")) {
            throw new PaymentNotSubmitted(ErrorCode.PROVIDER_CONFIG_INVALID);
        }
        return newClient(key);
    }
    StripeClient newClient(String key) {
        return StripeClient.builder().setApiKey(key).setConnectTimeout(connectTimeout()).setReadTimeout(readTimeout()).setMaxNetworkRetries(0).build();
    }
    private RequestOptions options(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).setConnectTimeout(connectTimeout()).setReadTimeout(readTimeout()).setMaxNetworkRetries(0).build();
    }
    // A setup call can make two HTTP requests, each with three attempts and 300ms of backoff.
    // Reserve more than that whole budget from callTimeout(), with no SDK retries on top.
    private int connectTimeout() { return Math.toIntExact(callTimeout().toMillis() / 30); }
    private int readTimeout() { return Math.toIntExact(callTimeout().toMillis() / 10); }
    @Override public Connection ping() {
        var client=client(false);
        return connection(client,settings.stripe(TenantContext.require()).orElseThrow().mode());
    }
    /**
     * PAY-10 (S17 R-17-05): the connection test of a key before it is saved: the same checks as a stored key (the mode, the key's
     * `sk_{mode}_` prefix, the hosted demo's test mode) before any network call, then the account the key reaches.
     */
    @Override public Connection ping(String mode,String secretKey) {
        if(!HostedDemoPolicy.permitsStripeMode(hostedDemo,stripeLiveEnabledClubIds,TenantContext.current(),mode)) { throw new PaymentNotSubmitted(ErrorCode.PROVIDER_CONFIG_INVALID); }
        if(!Set.of("test","live").contains(Objects.toString(mode,""))||secretKey==null||!secretKey.startsWith("sk_"+mode+"_")) {
            throw new PaymentNotSubmitted(ErrorCode.PROVIDER_CONFIG_INVALID);
        }
        return connection(newClient(secretKey),mode);
    }
    private Connection connection(StripeClient client,String mode) {
        var account=calls.call(()->client.v1().accounts().retrieveCurrent(options(null)));
        String name=account.getBusinessProfile()==null?null:account.getBusinessProfile().getName();
        return new Connection(true,account.getId(),name==null?account.getId():name,mode);
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
                .putAllMetadata(metadata).putExtraParam("allowed_payment_method_types", List.of("card"))
                .setExpiresAt(providerExpiry(request, clock).getEpochSecond());
        if (!"setup".equals(request.mode()) && request.customerEmail() != null) { params.setCustomerEmail(request.customerEmail()); }
        if ("setup".equals(request.mode())) {
            var customer = calls.call(() -> client.v1().customers().create(CustomerCreateParams.builder().putMetadata("memberId", request.memberId()).build(), options("customer:" + request.memberId())));
            params.setCustomer(customer.getId());
            params.setCurrency(request.metadata().getOrDefault("currency", "EUR").toString().toLowerCase(Locale.ROOT))
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
    /** PAY-08: Stripe accepts an `expires_at` from 30 minutes to 24 hours after it creates the Checkout session. */
    static final Duration STRIPE_MIN_EXPIRY = Duration.ofMinutes(30), STRIPE_MAX_EXPIRY = Duration.ofHours(24);
    /** PAY-08: room for the call's latency and for a difference between this server's clock and Stripe's, at either bound. */
    static final Duration EXPIRY_CLOCK_MARGIN = Duration.ofMinutes(5);
    /**
     * PAY-08 (owner decision of 13 Nov 2026; S04 R-04-26, S08 R-08-18): the `expires_at` of the Checkout session, so that the provider's
     * session ends with Core's deadline ({@link Request#expiresAt}) inside the window Stripe accepts.
     * <p>
     * A PAY_TO_BOOK booking's checkout (its metadata names the `bookingId`, as {@code SingleClassCharges} opens it) ends at the booking's
     * deadline (`bookings.paymentPendingMinutes` after the booking), but never sooner than Stripe's 30-minute minimum nor later than its
     * 24-hour maximum, counted from {@code clock}'s now with the margin: when Core's deadline passes first, P7 expires the provider's
     * session (S15 R-15-17) and E34 refunds a payment that still arrives. That checkout is asked for once, after the booking's commit.
     * <p>
     * Every other checkout (the signup's and S12's, card setups included) keeps S04's 24 hours: it ends the margin before Core's own
     * expiry, so a clock ahead of Stripe's never makes Stripe refuse it, and Stripe's expiry callback closes Core's side. The value comes
     * from the request alone: a retry after a lost answer (E5-T30) sends Stripe the very parameters of the first call.
     */
    static Instant providerExpiry(Request request, java.time.Clock clock) {
        if (!request.metadata().containsKey("bookingId")) { return request.expiresAt().minus(EXPIRY_CLOCK_MARGIN); }
        var now = clock.instant();
        var earliest = now.plus(STRIPE_MIN_EXPIRY).plus(EXPIRY_CLOCK_MARGIN);
        var latest = now.plus(STRIPE_MAX_EXPIRY).minus(EXPIRY_CLOCK_MARGIN);
        var deadline = request.expiresAt();
        return deadline.isBefore(earliest) ? earliest : deadline.isAfter(latest) ? latest : deadline;
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
        return refund(chargeId, amount, idempotencyKey, reason, null);
    }
    @Override public RefundResult refund(String chargeId, Money amount, String idempotencyKey, String reason, String operationId) {
        var client = client();
        var builder = RefundCreateParams.builder().setAmount(amount.amountMinor()).putMetadata("reason", reason);
        if (operationId != null) { builder.putMetadata("operationId", operationId); }
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
        // An existing checkout can still capture money after the club disabled new Stripe payments.
        var client = client(false); calls.call(() -> client.v1().checkout().sessions().expire(reference.get(), options("expire:" + sessionId)));
    }
}
