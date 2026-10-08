package com.agilityhub.core.payments.application.stripe;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.stripe.exception.StripeException;
import org.springframework.stereotype.Component;

/** Bounded retries of a rate-limited call; no provider message (which can contain customer data) reaches a response or log. */
@Component
public class StripeCalls {
    @FunctionalInterface public interface Call<T> { T execute() throws StripeException; }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }
    private final Pause pause;
    public StripeCalls() { this(Thread::sleep); }
    StripeCalls(Pause pause) { this.pause = pause; }
    public <T> T call(Call<T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return action.execute(); }
            catch (StripeException failure) {
                int status = failure.getStatusCode() == null ? 500 : failure.getStatusCode();
                if (status == 429 && attempt < 2) {
                    try { pause.sleep(100L << attempt); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ApiException(ErrorCode.INTERNAL_ERROR); }
                    continue;
                }
                // Authentication/permission rejection happens before execution, unlike ambiguous 400/5xx outcomes.
                if (status == 401 || status == 403) {
                    throw new com.agilityhub.core.payments.application.PaymentNotSubmitted(ErrorCode.PROVIDER_CONFIG_INVALID);
                }
                throw new ApiException(status == 429 ? ErrorCode.RATE_LIMITED
                        : status == 400 ? ErrorCode.PROVIDER_CONFIG_INVALID : ErrorCode.INTERNAL_ERROR);
            }
        }
    }
}
