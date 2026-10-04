package com.agilityhub.core.payments.application.ports;

/** Privacy vertical handoff: enqueue the deletion of the member's provider customer before removing its local reference. */
public interface PaymentPrivacyPort {
    void forgetCustomer(String customerId);
}
