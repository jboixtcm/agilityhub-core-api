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
        var training = defaults.trainingConflicts();
        assertThat(training.findActiveBookings("ring", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))).isEmpty();
        assertThatCode(() -> training.cancelByClub(List.of("booking"), "RING_BLOCK")).doesNotThrowAnyException();
        assertThat(defaults.activityTitles().titles(Set.of("activity"), Locale.ENGLISH)).isEmpty();
    }
}
