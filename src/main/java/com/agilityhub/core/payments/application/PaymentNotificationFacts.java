package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Application projection for the composition root's notification adapter. */
@Service
public class PaymentNotificationFacts {
    private final UpfrontPaymentRepository payments;
    public PaymentNotificationFacts(UpfrontPaymentRepository payments) { this.payments = payments; }
    public Map<String, Object> upfront(String id) {
        return payments.findById(id).map(p -> Map.<String, Object>of("amount", p.amountPaid(), "concept", p.concept(), "invoice_number", "")).orElse(Map.of());
    }
}
