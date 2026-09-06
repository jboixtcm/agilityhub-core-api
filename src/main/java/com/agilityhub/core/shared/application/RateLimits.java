package com.agilityhub.core.shared.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Per-instance token buckets for the E0 single-instance deployment. */
public final class RateLimits {
    public enum Route { TOKEN, BRANDING, PUBLIC, ME, MAGIC_LINK_EMAIL, MAGIC_LINK_IP }
    public record Limit(long capacity, Duration period) {
        public Limit {
            if (capacity < 1 || period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("Rate limits require positive capacity and period");
            }
        }
    }
    private record Key(Route route, String subject) { }
    private final boolean enabled;
    private final Map<Route, Limit> limits;
    private final TimeMeter time;
    private final Cache<Key, Bucket> buckets;

    public RateLimits(boolean enabled, Map<Route, Limit> limits, Clock clock) {
        this.enabled = enabled;
        this.limits = Map.copyOf(limits);
        this.time = new TimeMeter() {
            @Override public long currentTimeNanos() { return Math.multiplyExact(clock.millis(), 1_000_000L); }
            @Override public boolean isWallClockBased() { return true; }
        };
        Duration longest = limits.values().stream().map(Limit::period).max(Duration::compareTo).orElseThrow();
        this.buckets = Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(longest)
                .ticker(time::currentTimeNanos).build();
    }

    /** Zero means allowed; otherwise return a rounded-up Retry-After in seconds. */
    public long retryAfter(Route route, String subject) {
        if (!enabled) { return 0; }
        var bucket = buckets.get(new Key(route, subject), key -> {
            Limit limit = limits.get(key.route());
            return Bucket.builder().withCustomTimePrecision(time)
                    .addLimit(bandwidth -> bandwidth.capacity(limit.capacity()).refillIntervally(limit.capacity(), limit.period())).build();
        });
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? 0 : Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
    }
}
