package com.agilityhub.core.clubs.scheduling.application.ports;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * The S06 port null objects answer «nothing» until the owning context registers its adapter (S08 since E5-T02/T03,
 * S09 and S07 later). The application context never instantiates the replaced ones, so they are exercised here.
 */
class SchedulingPortDefaultsTest {
    @Test void E4_T03_nullObjectsAnswerEmptyUntilTheOwningContextsReplaceThem() {
        var defaults = new SchedulingPortDefaults();
        var bookings = defaults.classBookings();
        assertThat(bookings.activeBookings("class")).isEmpty();
        assertThat(bookings.liveWaitlist("class")).isEmpty();
        assertThat(bookings.waitlistEntries(List.of("entry"))).isEmpty();
        var effects = bookings.cancelAllByClub("class", "CLUB_MANUAL", "account");
        assertThat(effects.bookings()).isEmpty(); assertThat(effects.waitlist()).isEmpty();
        var courses = defaults.coursePlacements();
        assertThat(courses.ringOf("placement")).isEmpty();
        assertThat(courses.planned("placement", Locale.ENGLISH)).isEmpty();
        assertThat(courses.mounted()).isEmpty();
        var training = defaults.trainingConflicts();
        assertThat(training.findActiveBookings("ring", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))).isEmpty();
        assertThatCode(() -> training.cancelByClub(List.of("booking"), "RING_BLOCK")).doesNotThrowAnyException();
        assertThat(defaults.activityTitles().titles(Set.of("activity"), Locale.ENGLISH)).isEmpty();
        // E5-T10: the batched reads default to the per-class ones, with every requested class as a key.
        assertThat(bookings.activeBookingsByClass(List.of("a", "b"))).containsOnlyKeys("a", "b").allSatisfy((id, refs) -> assertThat(refs).isEmpty());
        assertThat(bookings.clubCancelledByClass(List.of("a"))).containsOnlyKeys("a").allSatisfy((id, refs) -> assertThat(refs).isEmpty());
        assertThat(bookings.bookings(List.of("booking"))).isEmpty();
    }

    @Test void T_16_13_theCalendarShowsOnlyDaysThatOverlapTheMountedPeriod() {
        var builtAt = Instant.parse("2026-11-22T09:00:00Z");
        var expiresAt = Instant.parse("2026-11-24T00:00:00Z");
        var setup = new CoursePlacementPort.Mounted("setup", builtAt, expiresAt);
        var firstDay = builtAt.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        assertThat(setup.overlaps(firstDay.minusSeconds(86400), firstDay)).isFalse();
        assertThat(setup.overlaps(firstDay, builtAt)).isFalse();
        assertThat(setup.overlaps(firstDay, firstDay.plusSeconds(86400))).isTrue();
        assertThat(setup.overlaps(firstDay.plusSeconds(86400), expiresAt)).isTrue();
        assertThat(setup.overlaps(expiresAt, expiresAt.plusSeconds(86400))).isFalse();
    }
}
