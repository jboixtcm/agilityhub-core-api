package com.agilityhub.core.clubs.messaging.domain;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMM-10 (owner decision of 13 Nov 2026, S11 R-11-07): the push TTL per code. A class reminder never outlives its class start
 * (the club's TTL, cut there); a seat offer lives exactly until its confirm-by deadline, beyond the club's TTL too; without a
 * deadline (an ALL_AT_ONCE offer, any other code) the club's TTL; a deadline that has passed sends nothing.
 */
class PushTtlTest {
    static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    static final Duration CLUB = Duration.ofMinutes(1440);

    @Test void COMM_10_aReminderIsCutAtItsClassStartAndNeverSentAfterIt() {
        assertThat(PushTtl.of("N-13", NOW.plus(Duration.ofHours(2)), CLUB, NOW)).contains(Duration.ofHours(2));
        assertThat(PushTtl.of("N-13", NOW.plus(Duration.ofHours(30)), CLUB, NOW)).contains(CLUB);
        assertThat(PushTtl.of("N-13", NOW.plusSeconds(1), CLUB, NOW)).contains(Duration.ofSeconds(1));
        assertThat(PushTtl.of("N-13", NOW, CLUB, NOW)).isEmpty();
        assertThat(PushTtl.of("N-13", NOW.minusSeconds(1), CLUB, NOW)).isEmpty();
        // A club that shortens its TTL keeps the shorter one.
        assertThat(PushTtl.of("N-13", NOW.plus(Duration.ofHours(2)), Duration.ofMinutes(30), NOW)).contains(Duration.ofMinutes(30));
    }

    @Test void COMM_10_aSeatOfferLivesUntilItsDeadlineAndEverythingElseKeepsTheClubsTtl() {
        assertThat(PushTtl.of("N-15", NOW.plus(Duration.ofMinutes(20)), CLUB, NOW)).contains(Duration.ofMinutes(20));
        assertThat(PushTtl.of("N-15", NOW.plus(Duration.ofHours(36)), Duration.ofMinutes(30), NOW)).contains(Duration.ofHours(36));
        assertThat(PushTtl.of("N-15", NOW.minusSeconds(1), CLUB, NOW)).isEmpty();
        assertThat(PushTtl.of("N-15", null, CLUB, NOW)).contains(CLUB);
        for (String code : new String[] {"N-13", "N-24", "N-33"}) { assertThat(PushTtl.of(code, null, CLUB, NOW)).contains(CLUB); }
        assertThat(PushTtl.of("N-24", null, Duration.ofMinutes(-5), NOW)).contains(Duration.ZERO);
        assertThat(PushTtl.of("N-24", null, null, NOW)).contains(Duration.ZERO);
        assertThat(PushTtl.PAST_DEADLINE).isEqualTo("Push deadline passed");
    }
}
