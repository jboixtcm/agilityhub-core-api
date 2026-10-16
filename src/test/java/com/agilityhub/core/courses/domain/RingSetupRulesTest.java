package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseTypes.SetupKind;
import com.agilityhub.core.courses.domain.RingSetupRules.Source;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E9-T03: S16 R-16-07/08's expiry by club-local day, the sources of a setup and R-16-09's `hasViewer`. */
class RingSetupRulesTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static Instant madrid(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }

    @Test void T_16_08_aSetupExpiresAtTheStartOfTheClubDayAfterTheAutomaticPeriodOrItsLastDay() {
        // R-16-07's example: registered on Monday 5-10 with seven days, it ends when Monday 12-10 starts in Madrid.
        assertThat(RingSetupRules.expiresAt(madrid("2026-10-05T10:00"), null, 7, MADRID)).isEqualTo(madrid("2026-10-12T00:00"));
        // A registration late at night is still that club day (22:30 UTC on 4-10 is 00:30 on 5-10 in Madrid).
        assertThat(RingSetupRules.expiresAt(Instant.parse("2026-10-04T22:30:00Z"), null, 7, MADRID)).isEqualTo(madrid("2026-10-12T00:00"));
        // «Fins quan?» earlier than the automatic end: shown all of its last day.
        assertThat(RingSetupRules.expiresAt(madrid("2026-10-05T10:00"), LocalDate.parse("2026-10-07"), 7, MADRID)).isEqualTo(madrid("2026-10-08T00:00"));
        // Later than the automatic end: the automatic end wins (min).
        assertThat(RingSetupRules.expiresAt(madrid("2026-10-05T10:00"), LocalDate.parse("2026-10-20"), 7, MADRID)).isEqualTo(madrid("2026-10-12T00:00"));
        // Today as the last day: it ends when tomorrow starts.
        assertThat(RingSetupRules.expiresAt(madrid("2026-10-05T10:00"), LocalDate.parse("2026-10-05"), 7, MADRID)).isEqualTo(madrid("2026-10-06T00:00"));
        // Across the end of summer time (25-10 in Madrid): still midnight local time.
        assertThat(RingSetupRules.expiresAt(madrid("2026-10-20T10:00"), null, 7, MADRID)).isEqualTo(madrid("2026-10-27T00:00"));
    }

    @Test void T_16_08_aMidnightInADstGapMovesForward() {
        // Santiago de Chile skips 00:00–01:00 on Sunday 6-9-2026: that day starts at 01:00 local.
        var santiago = ZoneId.of("America/Santiago");
        var end = RingSetupRules.expiresAt(LocalDateTime.parse("2026-08-30T12:00").atZone(santiago).toInstant(), null, 7, santiago);
        assertThat(end.atZone(santiago).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-09-06T01:00"));
    }

    @Test void T_16_08_expectedUntilIsTodayOrLater() {
        var today = LocalDate.parse("2026-10-05");
        assertThat(RingSetupRules.expectedUntilValid(null, today)).isTrue();
        assertThat(RingSetupRules.expectedUntilValid(today, today)).isTrue();
        assertThat(RingSetupRules.expectedUntilValid(today.plusDays(1), today)).isTrue();
        assertThat(RingSetupRules.expectedUntilValid(today.minusDays(1), today)).isFalse();
    }

    @Test void T_16_08_aSetupOtherThanEmptyNeedsASourceAndAnEmptyOneNamesNone() {
        assertThat(RingSetupRules.sources(null, null, null)).isEmpty();
        assertThat(RingSetupRules.sources("p", "c", "i")).containsExactly(Source.PLACEMENT, Source.COURSE, Source.IMAGE);
        assertThat(RingSetupRules.sources(null, "c", null)).containsExactly(Source.COURSE);
        assertThat(RingSetupRules.check(SetupKind.AGILITY, List.of())).isEqualTo(new RingSetupRules.SourceCheck(true, List.of()));
        assertThat(RingSetupRules.check(SetupKind.FUN, List.of(Source.IMAGE))).isEqualTo(new RingSetupRules.SourceCheck(false, List.of()));
        assertThat(RingSetupRules.check(SetupKind.EMPTY, List.of())).isEqualTo(new RingSetupRules.SourceCheck(false, List.of()));
        assertThat(RingSetupRules.check(SetupKind.EMPTY, List.of(Source.COURSE, Source.IMAGE)))
                .isEqualTo(new RingSetupRules.SourceCheck(false, List.of(Source.COURSE, Source.IMAGE)));
    }

    @Test void T_16_09_aPlacementOrACourseWithAModelHasAViewer() {
        assertThat(RingSetupRules.hasViewer("p", true, false)).isTrue();
        assertThat(RingSetupRules.hasViewer(null, true, true)).isTrue();
        assertThat(RingSetupRules.hasViewer(null, true, false)).isFalse();
        // E117 point 4: a deleted course has no viewer, placed or not.
        assertThat(RingSetupRules.hasViewer("p", false, false)).isFalse();
        assertThat(RingSetupRules.hasViewer(null, false, true)).isFalse();
    }
}
