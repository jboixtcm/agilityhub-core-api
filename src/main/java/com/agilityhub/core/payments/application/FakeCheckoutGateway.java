package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("(local | test) & !staging & !prod")
public class FakeCheckoutGateway implements PaymentProvider {
    private final Map<String,Request> requests=new ConcurrentHashMap<>();private final ObjectProvider<CheckoutService> checkout;
    private final java.util.Set<String> expired=ConcurrentHashMap.newKeySet();
    public FakeCheckoutGateway(ObjectProvider<CheckoutService> checkout) { this.checkout=checkout; }
    public Request request(String id) { var result=requests.get(id);if(result==null) throw new ApiException(ErrorCode.NOT_FOUND);return result; }
    /** Whether the provider was asked to expire (or expired) the session {@code id}. */
    public boolean expired(String id) { return expired.contains(id); }
    @Override public String createCheckoutSession(Request request) { requests.put(request.sessionId(),request);return "https://checkout.test/"+request.sessionId(); }
    @Override public void complete(String id) { try(var tenant=TenantContext.open(request(id).clubId())) { checkout.getObject().complete(id,"fake_payment_"+id,Map.of("stripeCustomerId","fake_customer_"+id,"stripePaymentMethodId","fake_method_"+id,"last4","4242","brand","visa")); } }
    @Override public void expire(String id) { var clubId=request(id).clubId();expired.add(id);try(var tenant=TenantContext.open(clubId)) { checkout.getObject().expire(id); } }
}
