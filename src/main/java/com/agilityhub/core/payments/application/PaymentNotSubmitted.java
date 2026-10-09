package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

/**
 * Internal evidence of no money movement: local validation or definitive provider rejection. No new public code.
 * <p>
 * E8-T11: {@link #definitive()} tells a rejection of the request itself (a card or invalid-request error, which the same call
 * will meet again) from an outage of the club's provider access (a missing, invalid, revoked or unauthorised key, rate limiting,
 * a disabled provider), which passes once the configuration is repaired or the limit resets.
 */
public final class PaymentNotSubmitted extends ApiException {
    private final boolean definitive;
    public PaymentNotSubmitted(ErrorCode code) { this(code, false); }
    private PaymentNotSubmitted(ErrorCode code, boolean definitive) { super(code); this.definitive = definitive; }
    /** Stripe refused the request's content: retrying the same call cannot succeed. */
    public static PaymentNotSubmitted refused(ErrorCode code) { return new PaymentNotSubmitted(code, true); }
    public boolean definitive() { return definitive; }
}
