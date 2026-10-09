package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.jobs.JobActionMarks;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobItem;
import com.agilityhub.core.platform.application.jobs.JobRunRecorder;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivor of {@link BillingReminderJob} (S15 P10; T-12-24): an item out of scope at apply time has no effect. */
class BillingReminderJobSurvivorsTest {
    final JobActionMarks marks = mock(JobActionMarks.class);
    final BillingEvents events = mock(BillingEvents.class);
    final BillingReminderJob job = new BillingReminderJob(mock(BillingCensusAccess.class), mock(RemittanceRepository.class), marks, events,
            mock(CensusClubSettings.class));

    @Test void T_12_24_aReminderNoLongerDueAtApplyTimeHasNoEffect() {
        // `billing.remittanceReminderDay = 0` turns the reminder off, so the planned item is out of scope when it is applied.
        var config = new ClubConfig(null, Map.of("billing.remittanceReminderDay", 0), Set.of(), null, Map.of());
        var context = new JobContext("club-a", ZoneOffset.UTC, Instant.parse("2026-08-25T06:00:00Z"), LocalDate.of(2026, 8, 25), false, config,
                mock(JobRunRecorder.class), "job-run-1");

        var effect = job.apply(context, new JobItem("Club", "club-a", "REMIND_BILLING"));

        assertThat(effect).isNotNull();
        assertThat(effect.action()).isEqualTo("NOT_IN_SCOPE");
        assertThat(effect.counters()).isEmpty();
        verifyNoInteractions(marks, events);
    }
}
