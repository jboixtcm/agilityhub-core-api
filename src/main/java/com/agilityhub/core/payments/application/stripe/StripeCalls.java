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
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new com.agilityhub.core.payments.application.PaymentNotSubmitted(ErrorCode.INTERNAL_ERROR);
                    }
                    continue;
                }
                // Stripe rejects rate limits before idempotency and does not execute lock-timeout 429s either.
                // SDK retries are disabled; this loop retries only 429, so all earlier calls were also rejected.
                if (status == 429) {
                    throw new com.agilityhub.core.payments.application.PaymentNotSubmitted(ErrorCode.RATE_LIMITED);
                }
                // Typed content/card rejection created no successful money movement. A concurrent-key
                // rejection is different: the other request may still execute. Unknown 4xx stays uncertain.
                boolean rejected = failure instanceof com.stripe.exception.CardException
                        || failure instanceof com.stripe.exception.InvalidRequestException
                        && !"idempotency_key_in_use".equals(failure.getCode());
                if (rejected) {
                    throw com.agilityhub.core.payments.application.PaymentNotSubmitted.refused(ErrorCode.PROVIDER_CONFIG_INVALID);
                }
                // An invalid, revoked or unauthorised key is an outage of the club's access, repaired outside the request (E8-T11).
                if (status == 401 || status == 403) {
                    throw new com.agilityhub.core.payments.application.PaymentNotSubmitted(ErrorCode.PROVIDER_CONFIG_INVALID);
                }
                // IdempotencyException, ApiConnectionException and ApiException may hide an earlier
                // effect. Retry the same command within its safe window, then reconcile by webhook.
                // The cause stays for telemetry; exception values never leave the process (SentryPrivacyConfiguration).
                throw new ApiException(status == 400 ? ErrorCode.PROVIDER_CONFIG_INVALID : ErrorCode.INTERNAL_ERROR, failure);
            }
        }
    }
}
