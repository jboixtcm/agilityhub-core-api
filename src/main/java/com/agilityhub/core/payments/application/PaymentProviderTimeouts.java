package com.agilityhub.core.payments.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * CONVENCIONS_API §7 (ruling E80): the application does not start with a payment provider whose calls could outlive the
 * Idempotency-Key's claim ({@link PaymentProvider#requireTimeout}). Without a provider there is nothing to check.
 */
@Component
public class PaymentProviderTimeouts implements SmartInitializingSingleton {
    private final ObjectProvider<PaymentProvider> providers;
    public PaymentProviderTimeouts(ObjectProvider<PaymentProvider> providers) { this.providers = providers; }
    @Override public void afterSingletonsInstantiated() { providers.orderedStream().forEach(PaymentProvider::requireTimeout); }
}
