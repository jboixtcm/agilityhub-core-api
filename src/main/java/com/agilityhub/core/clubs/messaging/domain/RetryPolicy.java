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

    private RetryPolicy() { }

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
