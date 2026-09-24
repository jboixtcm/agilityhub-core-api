package com.agilityhub.core.shared.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Per-instance token buckets for the E0 single-instance deployment. */
public final class RateLimits {
    public enum Route { TOKEN, BRANDING, PUBLIC, ME, MAGIC_LINK_EMAIL, MAGIC_LINK_IP, SIGNUP_IDENTITY, SIGNUP_FAMILY, SIGNUP_UPLOAD, SIGNUP_SUBMIT, SIGNUP_DAILY, SIGNUP_CHECKOUT, SIGNUP_TOWNS,
        /** E3-T09: the anonymous signup mails (N-39, the applicant's N-01) per club, notification and recipient. */
        SIGNUP_RECIPIENT }
    public record Limit(long capacity, Duration period) {
        public Limit {
            if (capacity < 1 || period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("Rate limits require positive capacity and period");
            }
        }
    }
    /**
     * R-04-20: the keys of the `signup.rateLimit` parameter (CATALEG_PARAMETRES) and the routes they limit. The catalog
     * default is the Cànic's; a missing or non-positive key keeps it.
     */
    private static final Map<String, Route> SIGNUP_KEYS = Map.of("identityChecksPerHour", Route.SIGNUP_IDENTITY,
            "familyGroupLookupsPerHour", Route.SIGNUP_FAMILY, "uploadUrlsPerHour", Route.SIGNUP_UPLOAD, "signupPerHour", Route.SIGNUP_SUBMIT,
            "signupPerDay", Route.SIGNUP_DAILY, "checkoutSessionsAnonymousPerHour", Route.SIGNUP_CHECKOUT, "townsPerHour", Route.SIGNUP_TOWNS);
    private record Key(Route route, String subject, Limit limit) { }
    private final boolean enabled;
    private final Map<Route, Limit> limits;
    private final TimeMeter time;
    private final Cache<Key, Bucket> buckets;

    public RateLimits(boolean enabled, Map<Route, Limit> limits, Clock clock) {
        this.enabled = enabled;
        var policies = new EnumMap<Route,Limit>(Route.class); policies.putAll(limits);
        policies.putIfAbsent(Route.SIGNUP_IDENTITY,new Limit(10,Duration.ofHours(1)));
        policies.putIfAbsent(Route.SIGNUP_FAMILY,new Limit(20,Duration.ofHours(1)));
        policies.putIfAbsent(Route.SIGNUP_UPLOAD,new Limit(30,Duration.ofHours(1)));
        policies.putIfAbsent(Route.SIGNUP_SUBMIT,new Limit(5,Duration.ofHours(1)));
        policies.putIfAbsent(Route.SIGNUP_DAILY,new Limit(20,Duration.ofDays(1)));
        policies.putIfAbsent(Route.SIGNUP_CHECKOUT,new Limit(10,Duration.ofHours(1)));
        policies.putIfAbsent(Route.SIGNUP_TOWNS,new Limit(60,Duration.ofHours(1)));
        // Proposed catalog key `signup.rateLimit.notificationsPerRecipientPerHour` (E3-T09 report); a constant until the catalog has it.
        policies.putIfAbsent(Route.SIGNUP_RECIPIENT,new Limit(3,Duration.ofHours(1)));
        this.limits = Map.copyOf(policies);
        this.time = new TimeMeter() {
            @Override public long currentTimeNanos() { return Math.multiplyExact(clock.millis(), 1_000_000L); }
            @Override public boolean isWallClockBased() { return true; }
        };
        Duration longest = this.limits.values().stream().map(Limit::period).max(Duration::compareTo).orElseThrow();
        this.buckets = Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(longest)
                .ticker(time::currentTimeNanos).build();
    }

    /** Zero means allowed; otherwise return a rounded-up Retry-After in seconds. */
    public long retryAfter(Route route, String subject) { return retryAfter(route, subject, limits.get(route)); }

    /**
     * {@link #retryAfter(Route, String)} with the club's own limit (`signup.rateLimit`). A changed limit opens a new bucket:
     * the capacity always matches the parameter in force.
     */
    public long retryAfter(Route route, String subject, Limit limit) {
        if (!enabled) { return 0; }
        var bucket = buckets.get(new Key(route, subject, limit), key -> Bucket.builder().withCustomTimePrecision(time)
                .addLimit(bandwidth -> bandwidth.capacity(key.limit().capacity()).refillIntervally(key.limit().capacity(), key.limit().period())).build());
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? 0 : Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
    }

    /** The limit of {@code route} for a club whose `signup.rateLimit` is {@code parameter} (null: the defaults). */
    public Limit limit(Route route, Map<?, ?> parameter) {
        Limit fallback = limits.get(route);
        if (parameter == null) { return fallback; }
        for (var entry : SIGNUP_KEYS.entrySet()) {
            if (entry.getValue() == route && parameter.get(entry.getKey()) instanceof Number value && value.longValue() > 0) {
                return new Limit(value.longValue(), fallback.period());
            }
        }
        return fallback;
    }
}
