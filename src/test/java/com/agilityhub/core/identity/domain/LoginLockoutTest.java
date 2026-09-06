package com.agilityhub.core.identity.domain;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoginLockoutTest {
    @Test void T_01_06_progressesThroughFifteenThirtySixtyMinutesAndCapsAtOneDay() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var state = LoginLockout.empty();
        for (int round = 0; round < 14; round++) {
            for (int n = 0; n < 4; n++) { state = state.failed(now, 5, 15); assertThat(state.locked(now)).isFalse(); }
            state = state.failed(now, 5, 15);
            long minutes = Math.min(1440, 15L << Math.min(round, 11));
            assertThat(state.retryAfter(now)).isEqualTo(minutes * 60);
            assertThat(state.failed(now, 5, 15)).isEqualTo(state);
            now = now.plus(Duration.ofMinutes(minutes));
            assertThat(state.locked(now)).isFalse();
        }
    }
    @Test void T_01_06_failuresOutsideTheWindowDoNotAccumulateAndConfiguredLimitsApply() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var state = LoginLockout.empty().failed(now, 3, 10).failed(now, 3, 10);
        assertThat(state.failed(now.plusSeconds(600), 3, 10).failures()).isEqualTo(1);
        assertThat(state.failed(now.plusSeconds(599), 3, 10).lockedUntil()).isEqualTo(now.plusSeconds(1199));
    }
}
