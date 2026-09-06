package com.agilityhub.core.support;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockClockTest {

    @Test
    void E0_T02_zoneViewsShareMutableTimeAcrossDaylightSavingTransition() {
        var clock = new MockClock(Instant.parse("2026-10-25T00:30:00Z"));
        var clubClock = clock.withZone(ZoneId.of("Europe/Madrid"));
        assertThat(ZonedDateTime.now(clubClock).getOffset().getTotalSeconds()).isEqualTo(7200);

        clock.advance(Duration.ofHours(1));
        assertThat(clubClock.instant()).isEqualTo(clock.instant());
        assertThat(ZonedDateTime.now(clubClock).getOffset().getTotalSeconds()).isEqualTo(3600);

        clubClock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
