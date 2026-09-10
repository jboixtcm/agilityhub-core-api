package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.*;

/** A14: Stripe is supplied in E8; this port describes the payment/setup checkout handoff. */
public interface PaymentProvider {
    record Item(String paymentId,String description,Money amount) { }
    record Request(String sessionId,String clubId,String memberId,String mode,List<Item> lines,String customerEmail,
            String clientReferenceId,Map<String,Object> metadata,String setupFutureUsage,String successUrl,String cancelUrl,Instant expiresAt) { }
    String createCheckoutSession(Request request);
    void complete(String sessionId);
    void expire(String sessionId);
}
