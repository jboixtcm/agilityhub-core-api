package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

/** Internal evidence of non-execution: local validation or explicit provider authentication rejection. No new public code. */
public final class PaymentNotSubmitted extends ApiException {
    public PaymentNotSubmitted(ErrorCode code) { super(code); }
}
