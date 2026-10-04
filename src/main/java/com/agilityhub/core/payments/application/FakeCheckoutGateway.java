package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

public class FakeCheckoutGateway implements PaymentProvider {
    private final Map<String,Request> requests=new ConcurrentHashMap<>();private final ObjectProvider<CheckoutService> checkout;
    private final java.util.Set<String> expired=ConcurrentHashMap.newKeySet();
    public FakeCheckoutGateway(ObjectProvider<CheckoutService> checkout) { this.checkout=checkout; }
    public Request request(String id) { var result=requests.get(id);if(result==null) throw new ApiException(ErrorCode.NOT_FOUND);return result; }
    /** Whether the provider was asked to expire (or expired) the session {@code id}. */
    public boolean expired(String id) { return expired.contains(id); }
    /**
     * Stripe's idempotency contract ({@link PaymentProvider#createCheckoutSession}, E5-T30): asked again for a known `sessionId`
     * with the same parameters it answers the same session; with other parameters it refuses (Stripe's idempotency error) and
     * keeps the session it opened.
     */
    @Override public String createCheckoutSession(Request request) {
        var known=requests.putIfAbsent(request.sessionId(),request);
        if(known!=null&&!known.equals(request)) throw new IllegalStateException("Idempotency error: checkoutSessionId="+request.sessionId()+" was opened with other parameters");
        return "https://checkout.test/"+request.sessionId();
    }
    /** The fake answers at once; it declares the contract's timeout, as the E8 provider's HTTP client will set it (ruling E80). */
    @Override public java.time.Duration callTimeout() { return MAX_CALL_TIMEOUT; }
    /** The provider takes the payment now and confirms it at once. */
    @Override public void complete(String id) { complete(id,null); }
    /** The payment taken at {@code paidAt} (null: now), confirmed now: a delayed webhook (E5-T30 round 2, E79). */
    public void complete(String id,Instant paidAt) { completion(request(id).clubId(),id,paidAt); }
    /**
     * The confirmation of {@code id}, in club {@code clubId}, from a process that did not open the session (the CLI's
     * {@code checkout:fake-provider}, which a smoke runs against a live stack), so without this instance's requests.
     */
    public void completion(String clubId,String id,Instant paidAt) {
        try(var tenant=TenantContext.open(clubId)) { checkout.getObject().complete(id,"fake_payment_"+id,Map.of("stripeCustomerId","fake_customer_"+id,"stripePaymentMethodId","fake_method_"+id,"last4","4242","brand","visa"),paidAt); }
    }
    @Override public void expire(String id) { expiry(request(id).clubId(),id); }
    /** The provider's expiry of {@code id} in club {@code clubId}, from any process (as {@link #completion}). */
    public void expiry(String clubId,String id) { expired.add(id);try(var tenant=TenantContext.open(clubId)) { checkout.getObject().expire(id); } }
}
