package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

/** S04 section 5 only: amount/provider mutations belong to the transactional payment workflow. */
public enum UpfrontPaymentStatus {
    DUE, CHECKOUT_PENDING, PARTIAL, PAID, CANCELLED, REFUNDED;
    public UpfrontPaymentStatus transitionTo(UpfrontPaymentStatus next) {
        boolean allowed = switch (this) {
            case DUE -> next == CHECKOUT_PENDING || next == PAID || next == PARTIAL || next == CANCELLED;
            case CHECKOUT_PENDING -> next == PAID || next == DUE || next == CANCELLED;
            case PARTIAL -> next == PAID || next == CANCELLED;
            case PAID -> next == REFUNDED;
            case CANCELLED, REFUNDED -> false;
        };
        if (!allowed) { throw new ApiException(ErrorCode.INVALID_STATE); }
        return next;
    }
    public UpfrontPaymentStatus onRejection() {
        return switch (this) {
            case DUE, PARTIAL, CHECKOUT_PENDING -> transitionTo(CANCELLED);
            case PAID, CANCELLED, REFUNDED -> this;
        };
    }
}
