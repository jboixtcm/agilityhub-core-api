package com.agilityhub.core.clubs.messaging.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * COMM-10 (owner decision of 13 Nov 2026, S11 R-11-07): the TTL a push is sent with, per code. A class reminder (N-13) is never
 * delivered after the class start: the club's `messaging.push.ttlMinutes`, cut at the start. A seat offer with a deadline (N-15
 * in FIFO) lives until its confirm-by deadline, exactly. Every other push, and an N-15 without a deadline (ALL_AT_ONCE), keeps
 * the club's TTL. A push whose deadline has passed is not sent at all ({@link #PAST_DEADLINE}). The deadline is the notification
 * owner's (S08, S09), read at every attempt.
 */
public final class PushTtl {
    /** The `lastError` of a push not sent because its deadline passed (`SKIPPED_STALE`). */
    public static final String PAST_DEADLINE = "Push deadline passed";
    /** The seat offer (S08 R-08-14), which lives until its deadline even beyond the club's TTL. */
    static final String SEAT_OFFER = "N-15";

    private PushTtl() { }

    /**
     * @param deadline the owner's deadline of the notification (the class start of N-13, the `confirmBy` of N-15), or null
     * @param clubTtl  the club's `messaging.push.ttlMinutes`
     * @return the TTL to send with, or empty when the deadline is now or past (nothing is sent)
     */
    public static Optional<Duration> of(String code, Instant deadline, Duration clubTtl, Instant now) {
        Duration club = clubTtl == null || clubTtl.isNegative() ? Duration.ZERO : clubTtl;
        if (deadline == null) { return Optional.of(club); }
        Duration left = Duration.between(now, deadline);
        if (!left.isPositive()) { return Optional.empty(); }
        if (SEAT_OFFER.equals(code)) { return Optional.of(left); }
        return Optional.of(left.compareTo(club) < 0 ? left : club);
    }
}
