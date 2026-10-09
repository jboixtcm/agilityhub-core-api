package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.DogFollowupPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link BookingsAutoConfiguration}: the null objects S08 registers when S10 follow-up, S09
 * training or S07 activities are absent answer «nothing» (the TASKS-off shape of E6-T02, no activity in P8 scope).
 */
class BookingsAutoConfigurationSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    final BookingsAutoConfiguration configuration = new BookingsAutoConfiguration();

    @Test void E11_T06_withoutFollowupTheDogHasNoTasksAndTheSheetLeavesThemOut() {
        var followup = configuration.noFollowup();

        assertThat(followup.available()).isFalse();
        assertThat(followup.tasks("dog-duna")).isEqualTo(new DogFollowupPort.Tasks(0, 0, null));
        assertThat(followup.pendingTasks(List.of("dog-duna"))).isEmpty();
        assertThat(followup.instructorNote("dog-duna")).isEmpty();
        assertThat(followup.observations("dog-duna")).isEmpty();
    }

    @Test void E11_T06_withoutTrainingOrActivitiesTheHistoriesAreEmpty() {
        assertThat(configuration.noTrainingHistory().itemsFor(List.of("dog-duna"), NOW.minusSeconds(86_400))).isEmpty();
        assertThat(configuration.noTrainingStats().countDone("dog-duna", NOW.minusSeconds(86_400), NOW)).isZero();
        assertThat(configuration.noActivityHistory().itemsFor("member-laura", NOW.minusSeconds(86_400))).isEmpty();
    }

    @Test void E11_T06_withoutActivitiesNothingIsEverFinished() {
        var finishing = configuration.noActivityFinishing();

        assertThat(finishing.endedBy(NOW)).isEmpty();
        assertThat(finishing.finish("activity-1", NOW)).isFalse();
    }
}
