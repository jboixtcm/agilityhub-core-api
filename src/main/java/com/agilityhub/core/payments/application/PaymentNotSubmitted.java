package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

/** Internal evidence that validation failed before any provider request was sent. No new public error code. */
public final class PaymentNotSubmitted extends ApiException {
    public PaymentNotSubmitted(ErrorCode code) { super(code); }
}
