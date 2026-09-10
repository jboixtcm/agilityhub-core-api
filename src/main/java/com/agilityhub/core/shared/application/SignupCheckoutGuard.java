package com.agilityhub.core.shared.application;

/** Resolves the checkout module gate before anonymous capability/idempotency handling. */
public interface SignupCheckoutGuard {
    void requireBilling();
}
