package com.agilityhub.core.clubs.messaging.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * S11 R-11-09 retries (technical constants, not parameters): a retryable failure (timeout, 429, 5xx, a push `RETRYABLE`)
 * puts the delivery back to `QUEUED` after 1, 5, 15, 60 and 240 minutes; a delivery has at most {@value #MAX_ATTEMPTS}
 * send attempts, so the 5th failure is final (`FAILED` + `NotificationFailed`, T-11-09) and the last delay of the list is
 * the one a 6th attempt would wait. A non-retryable failure is final at once.
 */
public final class RetryPolicy {
    public static final int MAX_ATTEMPTS = 5;
    public static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofMinutes(60), Duration.ofMinutes(240));

    /**
     * COMM-07 (owner decision of 13 Nov 2026): a provider's quota answer for an e-mail (429, 402, Brevo's `not_enough_credits`)
     * is no longer final. It waits 1, 5, 15 and 60 minutes like any retryable failure, then every 60 minutes, for about a day:
     * the {@value #QUOTA_MAX_ATTEMPTS}th failing attempt is final (`FAILED`), some 24 hours after the first.
     */
    public static final int QUOTA_MAX_ATTEMPTS = 28;
    public static final Duration QUOTA_BACKOFF = Duration.ofMinutes(60);

    private RetryPolicy() { }

    /**
     * COMM-07: when to try again after a quota answer.
     *
     * @param attempts the send attempts made so far, the failing one included (≥ 1); a resend starts them again
     * @return the next attempt, or empty once {@value #QUOTA_MAX_ATTEMPTS} attempts have failed
     */
    public static Optional<Instant> nextQuotaAttempt(int attempts, Instant now) {
        if (attempts < 1) { throw new IllegalArgumentException("attempts must be positive"); }
        if (attempts >= QUOTA_MAX_ATTEMPTS) { return Optional.empty(); }
        return Optional.of(now.plus(attempts < MAX_ATTEMPTS ? BACKOFF.get(attempts - 1) : QUOTA_BACKOFF));
    }

    /**
     * @param attempts the send attempts made so far, the failing one included (≥ 1)
     * @return when to try again, or empty when the failure is final
     */
    public static Optional<Instant> nextAttempt(int attempts, boolean retryable, Instant now) {
        if (attempts < 1) { throw new IllegalArgumentException("attempts must be positive"); }
        if (!retryable || attempts >= MAX_ATTEMPTS) { return Optional.empty(); }
        return Optional.of(now.plus(BACKOFF.get(attempts - 1)));
    }
}
