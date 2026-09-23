package com.agilityhub.core.shared.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** S15 WP-15-E: the movable clock of `local`/`test` keeps flowing after being set or advanced. */
class OffsetClockTest {
    @Test void T_15_07_setAndAdvanceMoveTheSameOffsetForEveryZoneView() {
        var base = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var clock = new OffsetClock(base);
        assertThat(clock.instant()).isEqualTo(base.instant());
        clock.setInstant(Instant.parse("2026-10-05T05:30:00Z"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-05T05:30:00Z"));
        var madrid = clock.withZone(ZoneId.of("Europe/Madrid"));
        clock.advance(Duration.ofMinutes(90));
        assertThat(madrid.instant()).isEqualTo(Instant.parse("2026-10-05T07:00:00Z"));
        assertThat(madrid.getZone()).isEqualTo(ZoneId.of("Europe/Madrid"));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
