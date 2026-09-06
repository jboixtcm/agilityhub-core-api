package com.agilityhub.core.identity.domain;

import java.time.Duration;
import java.time.Instant;

/** R-01-08: a rolling failure window and exponential lock durations, capped at one day. */
public record LoginLockout(int failures, Instant windowStartedAt, Instant lockedUntil, int level) {
    public static LoginLockout empty() { return new LoginLockout(0, null, null, 0); }
    public boolean locked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
    public LoginLockout failed(Instant now, int maxAttempts, int minutes) {
        if (locked(now)) { return this; }
        boolean expired = windowStartedAt == null || !windowStartedAt.plus(Duration.ofMinutes(minutes)).isAfter(now);
        int count = expired ? 1 : failures + 1;
        Instant start = expired ? now : windowStartedAt;
        if (count < maxAttempts) { return new LoginLockout(count, start, null, level); }
        long duration = Math.min(1440, (long) minutes * (1L << Math.min(level, 11)));
        return new LoginLockout(0, null, now.plus(Duration.ofMinutes(duration)), Math.min(level + 1, 11));
    }
    public long retryAfter(Instant now) { return Math.max(1, (Duration.between(now, lockedUntil).toMillis() + 999) / 1000); }
}
