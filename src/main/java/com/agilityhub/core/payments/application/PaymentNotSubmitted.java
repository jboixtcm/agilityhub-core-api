package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

/** Internal evidence of no money movement: local validation or definitive provider rejection. No new public code. */
public final class PaymentNotSubmitted extends ApiException {
    public PaymentNotSubmitted(ErrorCode code) { super(code); }
}
