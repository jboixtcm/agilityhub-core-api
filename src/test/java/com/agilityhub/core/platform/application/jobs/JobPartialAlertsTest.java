package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.persistence.jobs.JobRun;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JOB-01 (owner decision of 13 Nov 2026, S15 R-15-10): which closed runs publish `JobFailed` (N-42 to the club's admins). A
 * FAILED run of any process, as before; a PARTIAL run only of the processes that affect members — the risk review, the
 * reminders, the expirations and the billing-related payment timeouts and remittance reminder; never a SUCCEEDED or SKIPPED one
 * (a missed window alerts from its own path). The cross-boundary part is `JobFrameworkIT.T_15_10_JOB_01_…`.
 */
class JobPartialAlertsTest {
    private static final Instant AT = Instant.parse("2026-10-05T05:30:00Z");
    static final EnumSet<JobName> MEMBER_FACING = EnumSet.of(JobName.RISK_REVIEW, JobName.REMINDERS, JobName.EXPIRATIONS, JobName.PAYMENT_TIMEOUTS,
            JobName.BILLING_REMINDER);

    private static JobRun run(JobName name, JobStatus status) {
        return new JobRun("run-1", "club-a", name, AT, "2026-10-05T07:30", "Europe/Madrid", JobTrigger.SCHEDULE, false,
                status, null, AT, AT, 0L, List.of(), List.of(), List.of(), null, List.of(), true, "holder", false);
    }

    @Test void T_15_10_JOB_01_aPartialRunAlertsOnlyForTheProcessesThatAffectMembers() {
        for (JobName name : JobName.values()) {
            assertThat(JobCatalog.alertsOnPartial(name)).as(name.name()).isEqualTo(MEMBER_FACING.contains(name));
            assertThat(JobRunner.alerts(run(name, JobStatus.FAILED))).as(name + " FAILED").isTrue();
            assertThat(JobRunner.alerts(run(name, JobStatus.PARTIAL))).as(name + " PARTIAL").isEqualTo(MEMBER_FACING.contains(name));
            for (JobStatus quiet : List.of(JobStatus.SUCCEEDED, JobStatus.SKIPPED, JobStatus.RUNNING)) {
                assertThat(JobRunner.alerts(run(name, quiet))).as(name + " " + quiet).isFalse();
            }
        }
    }
}
