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
    /**
     * PAY-02 (owner decision of 13 Nov 2026): a failure of {@link #call} that says the provider could not be reached or used,
     * not that the request is wrong: a connection error (the SDK gives it status 0) or a 5xx (the cause of the INTERNAL_ERROR
     * thrown below; a missing status reads as 500, as below), or a refusal of the club's access — 401, 403 — or a rate limit —
     * 429 — thrown as a non-definitive {@link com.agilityhub.core.payments.application.PaymentNotSubmitted} (E8-T11's outage,
     * which also covers a key the club stored that cannot be used). A content rejection (card, invalid request, another 4xx) or
     * any other failure is not.
     */
    public static boolean outage(RuntimeException failure) {
        if (failure instanceof com.agilityhub.core.payments.application.PaymentNotSubmitted rejected) { return !rejected.definitive(); }
        return failure instanceof ApiException && failure.getCause() instanceof StripeException cause
                && (cause instanceof com.stripe.exception.ApiConnectionException || cause.getStatusCode() == null || cause.getStatusCode() >= 500);
    }
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
