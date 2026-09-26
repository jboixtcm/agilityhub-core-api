package com.agilityhub.core.clubs.bookings.domain;

import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

/**
 * S10 R-10-05 «ha avisat», the six rows of the table over a class on Thu 15-10 18:50–19:50 (Madrid), with WAITLIST on,
 * live entries and a pack-paid booking. The spec table is written with `bookings.lateCancelThresholdMinutes = 120`
 * (R-10-05 «Paràmetres»); the catalog value is 240 (Jordi 05-09, CATALEG_PARAMETRES), which moves the in-time limit
 * to 14:50:00, so the same rows are asserted with both values, plus the 240 limit itself. No Spring, no Mongo.
 */
class NoticeRulesTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant STARTS = local("2026-10-15T18:50"), ENDS = local("2026-10-15T19:50");
    static Instant local(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }

    @ParameterizedTest(name = "threshold {0}, saved at {1}: late={3}, {4}, pack refunded={5}, waiting list told={6}, afterClassEnd={7}")
    @CsvSource(nullValues = "—", value = {
            // The R-10-05 table as written (120).
            "120, 2026-10-15T16:00,    170, false, CANCELLED,      true,  true,  false",
            "120, 2026-10-15T16:50:00, 120, false, CANCELLED,      true,  true,  false",
            "120, 2026-10-15T16:50:01, 119, true,  CANCELLED_LATE, false, true,  false",
            "120, 2026-10-15T18:20:00, 30,  true,  CANCELLED_LATE, false, false, false",
            "120, 2026-10-15T19:05,    -15, true,  CANCELLED_LATE, false, false, false",
            "120, 2026-10-15T20:30,    —,   true,  ACTIVE,         false, false, true",
            // The same rows with the catalog value (240): the first three are now late.
            "240, 2026-10-15T16:00,    170, true,  CANCELLED_LATE, false, true,  false",
            "240, 2026-10-15T16:50:00, 120, true,  CANCELLED_LATE, false, true,  false",
            "240, 2026-10-15T16:50:01, 119, true,  CANCELLED_LATE, false, true,  false",
            "240, 2026-10-15T18:20:00, 30,  true,  CANCELLED_LATE, false, false, false",
            "240, 2026-10-15T19:05,    -15, true,  CANCELLED_LATE, false, false, false",
            "240, 2026-10-15T20:30,    —,   true,  ACTIVE,         false, false, true",
            // The 240 limit: exactly 4 h before is still in time, one second later is late.
            "240, 2026-10-15T14:50:00, 240, false, CANCELLED,      true,  true,  false",
            "240, 2026-10-15T14:50:01, 239, true,  CANCELLED_LATE, false, true,  false"})
    void T_10_03_theSixRowsOfTheHaAvisatTable(int threshold, String savedAt, Integer minutesBefore, boolean late, BookingState booking, boolean packRefunded,
            boolean notifyWaitlist, boolean afterClassEnd) {
        var outcome = NoticeRules.decide(STARTS, ENDS, local(savedAt), threshold, true, 30, true, true);
        assertThat(outcome).isEqualTo(new NoticeRules.Outcome(afterClassEnd, late, minutesBefore, booking, packRefunded, !afterClassEnd, notifyWaitlist));
    }

    @ParameterizedTest(name = "WAITLIST {0}, live entries {1}, pack {2}")
    @CsvSource({"false, true, true, false, true", "true, false, true, false, true", "true, true, false, true, false"})
    void T_10_03_waitlistModuleEntriesAndPackDecideTheSideEffects(boolean waitlist, boolean entries, boolean pack, boolean told, boolean refunded) {
        var outcome = NoticeRules.decide(STARTS, ENDS, local("2026-10-15T14:00"), 240, waitlist, 30, entries, pack);
        assertThat(outcome.bookingState()).isEqualTo(BookingState.CANCELLED);
        assertThat(outcome.notifyWaitlist()).isEqualTo(told);
        assertThat(outcome.packRefunded()).isEqualTo(refunded);
        assertThat(outcome.seatReleased()).isTrue();
    }
}
