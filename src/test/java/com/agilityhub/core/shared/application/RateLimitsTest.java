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
}
