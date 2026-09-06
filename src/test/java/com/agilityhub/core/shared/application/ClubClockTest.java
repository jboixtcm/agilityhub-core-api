package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.MockClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClubClockTest {
    @Test void E0_T04_clubDatesUseTenantZoneAndInjectedClockAcrossDst() {
        var clock = new MockClock(Instant.parse("2030-03-31T00:30:00Z"));
        var clubs = new DefaultClubClock(clock, new MapTimeZoneProvider(Map.of(
                "club-a", ZoneId.of("Europe/Madrid"), "club-b", ZoneId.of("America/New_York"))));
        assertThat(clubs.today("club-a")).hasToString("2030-03-31");
        assertThat(clubs.today("club-b")).hasToString("2030-03-30");
        assertThat(clubs.now("club-a").getHour()).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(clubs.now("club-a").getHour()).isEqualTo(3);
        assertThatThrownBy(() -> clubs.today("unknown")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
    }
}
