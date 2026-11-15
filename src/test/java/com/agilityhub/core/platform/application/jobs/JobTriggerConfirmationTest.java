package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.platform.persistence.jobs.JobRunRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PRIV-02 (owner's decision of 13-11, S14 R-14-16, S15 R-15-09): a real manual run of a process that deletes personal data names a
 * recent dry run of it and repeats the counts it showed; the plan counts it returns are what the runner checks again under the lock.
 */
class JobTriggerConfirmationTest {
    static final Instant NOW = Instant.parse("2026-10-01T04:00:00Z");
    final JobRunner runner = mock(JobRunner.class);
    final JobRunRepository runs = mock(JobRunRepository.class);
    final JobTriggerService service = new JobTriggerService(runner, null, runs, Clock.fixed(NOW, ZoneOffset.UTC));
    final Job sweep = job(true);
    final Job reminders = job(false);
    static final Map<String, Long> SHOWN = Map.of("WOULD_ERASE_RETENTION", 2L, "WOULD_PURGE_AUDIT", 1L, "WOULD_PURGE_AUDIT_ENTRIES", 40L,
            "WOULD_DELETE_REMITTANCE_FILE", 0L, "membersErased", 0L, "auditEntriesPurged", 0L);

    static Job job(boolean confirmed) {
        var job = mock(Job.class);
        when(job.manualRunNeedsConfirmedDryRun()).thenReturn(confirmed);
        return job;
    }
    static JobRun run(String id, boolean dryRun, JobStatus status, Instant startedAt, Map<String, Long> counters) {
        var entries = counters.entrySet().stream().map(entry -> new JobRun.Entry(entry.getKey(), entry.getValue())).toList();
        return new JobRun(id, "club-a", JobName.RETENTION_SWEEP, startedAt, "2026-10-01T06:00", "Europe/Madrid", JobTrigger.MANUAL, dryRun, status, null,
                startedAt, startedAt, 0L, entries, List.of(), List.of(), "admin", List.of(), false, null, false);
    }
    static void stale(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.code()).isEqualTo(ErrorCode.SIMULATION_STALE);
            assertThat(refused.details()).containsEntry("reason", reason);
        });
    }

    @Test void PRIV_02_aProcessThatDeletesNoPersonalDataNeedsNoConfirmation() {
        when(runner.registered(JobName.REMINDERS)).thenReturn(Optional.of(reminders));
        assertThat(service.confirmedPlan(JobName.REMINDERS, null)).isNull();
        when(runner.registered(JobName.TEST_NOOP)).thenReturn(Optional.empty());
        assertThat(service.confirmedPlan(JobName.TEST_NOOP, null)).as("unknown: the runner answers JOB_UNKNOWN").isNull();
        verifyNoInteractions(runs);
    }

    @Test void PRIV_02_theSweepNeedsARecentSucceededDryRunOfItsOwnAndItsExactCounts() {
        when(runner.registered(JobName.RETENTION_SWEEP)).thenReturn(Optional.of(sweep));
        stale(() -> service.confirmedPlan(JobName.RETENTION_SWEEP, null), "DRY_RUN_REQUIRED");

        when(runs.forJob(JobName.RETENTION_SWEEP, "missing")).thenReturn(Optional.empty());
        stale(() -> service.confirmedPlan(JobName.RETENTION_SWEEP, new JobViews.JobRunConfirmation("missing", SHOWN)), "DRY_RUN_REQUIRED");
        // A real run, a failed dry run or one older than the window confirms nothing.
        when(runs.forJob(JobName.RETENTION_SWEEP, "real")).thenReturn(Optional.of(run("real", false, JobStatus.SUCCEEDED, NOW.minusSeconds(60), SHOWN)));
        when(runs.forJob(JobName.RETENTION_SWEEP, "failed")).thenReturn(Optional.of(run("failed", true, JobStatus.FAILED, NOW.minusSeconds(60), SHOWN)));
        when(runs.forJob(JobName.RETENTION_SWEEP, "old")).thenReturn(Optional.of(run("old", true, JobStatus.SUCCEEDED,
                NOW.minus(JobTriggerService.CONFIRMATION_WINDOW).minusSeconds(1), SHOWN)));
        for (String id : List.of("real", "failed", "old")) {
            stale(() -> service.confirmedPlan(JobName.RETENTION_SWEEP, new JobViews.JobRunConfirmation(id, SHOWN)), "DRY_RUN_REQUIRED");
        }

        when(runs.forJob(JobName.RETENTION_SWEEP, "dry")).thenReturn(Optional.of(run("dry", true, JobStatus.SUCCEEDED,
                NOW.minus(JobTriggerService.CONFIRMATION_WINDOW), SHOWN)));
        var fewer = new LinkedHashMap<>(SHOWN); fewer.put("WOULD_PURGE_AUDIT_ENTRIES", 39L);
        stale(() -> service.confirmedPlan(JobName.RETENTION_SWEEP, new JobViews.JobRunConfirmation("dry", fewer)), "COUNTS_MISMATCH");
        var missing = new LinkedHashMap<>(SHOWN); missing.remove("WOULD_ERASE_RETENTION");
        stale(() -> service.confirmedPlan(JobName.RETENTION_SWEEP, new JobViews.JobRunConfirmation("dry", missing)), "COUNTS_MISMATCH");
        var extra = new LinkedHashMap<>(SHOWN); extra.put("WOULD_EXPIRE_EXPORT", 1L);
        stale(() -> service.confirmedPlan(JobName.RETENTION_SWEEP, new JobViews.JobRunConfirmation("dry", extra)), "COUNTS_MISMATCH");

        // The dry run's own counts, as shown or without its zeros and real-run counters: its plan counts.
        var plan = Map.of("WOULD_ERASE_RETENTION", 2L, "WOULD_PURGE_AUDIT", 1L, "WOULD_PURGE_AUDIT_ENTRIES", 40L);
        assertThat(service.confirmedPlan(JobName.RETENTION_SWEEP, new JobViews.JobRunConfirmation("dry", SHOWN))).isEqualTo(plan);
        assertThat(service.confirmedPlan(JobName.RETENTION_SWEEP, new JobViews.JobRunConfirmation("dry", plan))).isEqualTo(plan);
        assertThat(JobTriggerService.CONFIRMATION_WINDOW).isEqualTo(Duration.ofMinutes(30));
    }

    @Test void PRIV_02_thePlanCountsAreTheClubsNonZeroWouldCounters() {
        var counters = new LinkedHashMap<String, Long>();
        counters.put("WOULD_DELETE", 3L); counters.put("WOULD_DELETE_platformItems", 1L); counters.put("WOULD_ABANDON", 0L);
        counters.put("orphanUploadsDeleted", 0L); counters.put("platformPass", 1L); counters.put("WOULD_NULL", null);
        assertThat(JobViews.plannedCounts(counters)).containsExactly(entry("WOULD_DELETE", 3L));
        assertThat(JobViews.plannedCounts(Map.of())).isEmpty();
    }
}
