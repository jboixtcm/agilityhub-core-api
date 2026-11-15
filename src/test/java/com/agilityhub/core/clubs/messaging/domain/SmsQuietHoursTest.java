package com.agilityhub.core.clubs.messaging.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * COMM-06 (owner decision of 13 Nov 2026, S11 R-11-06, T-11-06): the SMS quiet hours, 22:00–08:00 in the club's time zone
 * by default; a non-urgent SMS waits until their end, a seat offer with a deadline never waits.
 */
class SmsQuietHoursTest {
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid"), BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final SmsQuietHours NIGHT = SmsQuietHours.of("22:00", "08:00");

    @Test void T_11_06_COMM_06_aNonUrgentSmsInsideTheWindowWaitsUntilItsEndInTheClubsZone() {
        // 23:00 in Madrid (CET, UTC+1): tomorrow's 08:00, 07:00 UTC.
        assertThat(NIGHT.deferUntil(Instant.parse("2026-11-03T22:00:00Z"), MADRID)).contains(Instant.parse("2026-11-04T07:00:00Z"));
        // 03:00 in Madrid: the same day's 08:00.
        assertThat(NIGHT.deferUntil(Instant.parse("2026-11-04T02:00:00Z"), MADRID)).contains(Instant.parse("2026-11-04T07:00:00Z"));
        // 22:00 is inside (the start counts), 08:00 and 21:59 are outside.
        assertThat(NIGHT.deferUntil(Instant.parse("2026-11-03T21:00:00Z"), MADRID)).contains(Instant.parse("2026-11-04T07:00:00Z"));
        assertThat(NIGHT.deferUntil(Instant.parse("2026-11-04T07:00:00Z"), MADRID)).isEmpty();
        assertThat(NIGHT.deferUntil(Instant.parse("2026-11-03T20:59:00Z"), MADRID)).isEmpty();
        // Midday: no wait.
        assertThat(NIGHT.deferUntil(Instant.parse("2026-11-04T11:00:00Z"), MADRID)).isEmpty();
        // The same instant is night in Madrid and evening in Buenos Aires (UTC−3): each club's own clock.
        var instant = Instant.parse("2026-11-04T00:30:00Z");
        assertThat(NIGHT.deferUntil(instant, MADRID)).contains(Instant.parse("2026-11-04T07:00:00Z"));
        assertThat(NIGHT.deferUntil(instant, BUENOS_AIRES)).isEmpty();
    }

    @Test void T_11_06_COMM_06_theEndFollowsTheWallClockAcrossADstChange() {
        // Night of 24–25 October 2026: Madrid goes from CEST (UTC+2) to CET (UTC+1); 08:00 on the 25th is 07:00 UTC.
        assertThat(NIGHT.deferUntil(Instant.parse("2026-10-24T21:30:00Z"), MADRID)).contains(Instant.parse("2026-10-25T07:00:00Z"));
        // Night of 28–29 March 2026: CET to CEST; 08:00 on the 29th is 06:00 UTC.
        assertThat(NIGHT.deferUntil(Instant.parse("2026-03-28T22:30:00Z"), MADRID)).contains(Instant.parse("2026-03-29T06:00:00Z"));
    }

    @Test void T_11_06_COMM_06_aWindowWithinOneDayAndEqualBoundsMeaningNone() {
        var lunch = SmsQuietHours.of("13:00", "15:00");
        assertThat(lunch.contains(LocalTime.of(14, 0))).isTrue(); assertThat(lunch.contains(LocalTime.of(15, 0))).isFalse();
        assertThat(lunch.contains(LocalTime.of(12, 59))).isFalse(); assertThat(lunch.contains(LocalTime.of(23, 0))).isFalse();
        assertThat(lunch.deferUntil(Instant.parse("2026-11-04T13:00:00Z"), MADRID)).contains(Instant.parse("2026-11-04T14:00:00Z"));
        var none = SmsQuietHours.of("22:00", "22:00");
        for (int hour = 0; hour < 24; hour++) { assertThat(none.contains(LocalTime.of(hour, 0))).isFalse(); }
        assertThat(SmsQuietHours.of(null, "08:00")).isEqualTo(SmsQuietHours.NONE);
        assertThat(SmsQuietHours.of(" ", "08:00").deferUntil(Instant.parse("2026-11-03T22:00:00Z"), MADRID)).isEmpty();
        assertThat(SmsQuietHours.of(" 22:00 ", "08:00 ")).isEqualTo(NIGHT);
    }

    @Test void T_11_06_COMM_06_onlyASeatOfferWithADeadlineIsUrgent() {
        assertThat(SmsQuietHours.urgent("N-15", Map.of("mode", "FIFO", "confirm_by", "8:30"))).isTrue();
        assertThat(SmsQuietHours.urgent("N-15", Map.of("mode", "ALL_AT_ONCE"))).isFalse();
        var blank = new HashMap<String, Object>(); blank.put("confirm_by", " ");
        assertThat(SmsQuietHours.urgent("N-15", blank)).isFalse();
        assertThat(SmsQuietHours.urgent("N-15", null)).isFalse();
        // A class cancelled or changed by the club, a booking made for the member: not urgent, they wait.
        for (String code : new String[] {"N-08a", "N-08b", "N-32c", "N-32d", "N-36", "N-47", "N-24"}) {
            assertThat(SmsQuietHours.urgent(code, Map.of("confirm_by", "8:30"))).as(code).isFalse();
        }
    }
}
