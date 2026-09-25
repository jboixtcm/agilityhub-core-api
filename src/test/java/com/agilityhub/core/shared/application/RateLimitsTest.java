package com.agilityhub.core.shared.application;

import com.agilityhub.core.support.MockClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RateLimitsTest {
    @Test void T_01_15_configuredCapacityIsAtomicUnderConcurrentRequestsAndRefillsUsingInjectedClock() throws Exception {
        var clock = new MockClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limits = new RateLimits(true, Map.of(RateLimits.Route.TOKEN, new RateLimits.Limit(3, Duration.ofSeconds(2))), clock);
        try (var workers = Executors.newFixedThreadPool(10)) {
            var calls = IntStream.range(0, 20).mapToObj(index ->
                    workers.submit(() -> limits.retryAfter(RateLimits.Route.TOKEN, "203.0.113.5"))).toList();
            int accepted = 0;
            for (var call : calls) { if (call.get() == 0) { accepted++; } }
            assertThat(accepted).isEqualTo(3);
        }
        clock.advance(Duration.ofMillis(1001));
        assertThat(limits.retryAfter(RateLimits.Route.TOKEN, "203.0.113.5")).isEqualTo(1);
        clock.advance(Duration.ofMillis(999));
        assertThat(limits.retryAfter(RateLimits.Route.TOKEN, "203.0.113.5")).isZero();
    }

    @Test void T_01_15_invalidLimitsFailFastAndDisabledLimitsNeverConsume() {
        for (Duration period : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new RateLimits.Limit(1, period)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RateLimits.Limit(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        var limits = new RateLimits(false, Map.of(RateLimits.Route.TOKEN, new RateLimits.Limit(1, Duration.ofMinutes(1))),
                java.time.Clock.systemUTC());
        for (int index = 0; index < 31; index++) { assertThat(limits.retryAfter(RateLimits.Route.TOKEN, "203.0.113.5")).isZero(); }
    }

    @Test void R_04_20_signupRateLimitParameterSetsTheClubsCapacityAndKeepsThePeriod() {
        var clock = new MockClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limits = new RateLimits(true, Map.of(), clock);
        var parameter = Map.of("identityChecksPerHour", 2, "signupPerDay", 7, "townsPerHour", 0, "uploadUrlsPerHour", "30");
        assertThat(limits.limit(RateLimits.Route.SIGNUP_IDENTITY, parameter)).isEqualTo(new RateLimits.Limit(2, Duration.ofHours(1)));
        assertThat(limits.limit(RateLimits.Route.SIGNUP_DAILY, parameter)).isEqualTo(new RateLimits.Limit(7, Duration.ofDays(1)));
        // Missing, non-positive or non-numeric keys keep the catalog default.
        assertThat(limits.limit(RateLimits.Route.SIGNUP_TOWNS, parameter)).isEqualTo(new RateLimits.Limit(60, Duration.ofHours(1)));
        assertThat(limits.limit(RateLimits.Route.SIGNUP_UPLOAD, parameter)).isEqualTo(new RateLimits.Limit(30, Duration.ofHours(1)));
        assertThat(limits.limit(RateLimits.Route.SIGNUP_FAMILY, null)).isEqualTo(new RateLimits.Limit(20, Duration.ofHours(1)));
        assertThat(limits.limit(RateLimits.Route.SIGNUP_RECIPIENT, parameter)).isEqualTo(new RateLimits.Limit(3, Duration.ofHours(1)));
        // E3-T09 round 2: the per-recipient mail cap is the `notificationsPerRecipientPerHour` key.
        assertThat(limits.limit(RateLimits.Route.SIGNUP_RECIPIENT, Map.of("notificationsPerRecipientPerHour", 5))).isEqualTo(new RateLimits.Limit(5, Duration.ofHours(1)));
        var two = limits.limit(RateLimits.Route.SIGNUP_IDENTITY, parameter);
        assertThat(limits.retryAfter(RateLimits.Route.SIGNUP_IDENTITY, "club:203.0.113.5", two)).isZero();
        assertThat(limits.retryAfter(RateLimits.Route.SIGNUP_IDENTITY, "club:203.0.113.5", two)).isZero();
        assertThat(limits.retryAfter(RateLimits.Route.SIGNUP_IDENTITY, "club:203.0.113.5", two)).isEqualTo(3600);
        // A changed parameter opens a bucket with the new capacity.
        assertThat(limits.retryAfter(RateLimits.Route.SIGNUP_IDENTITY, "club:203.0.113.5", new RateLimits.Limit(3, Duration.ofHours(1)))).isZero();
    }
}
