package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.application.audit.AuditableLoader;
import com.agilityhub.core.platform.persistence.jobs.JobRunRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** S15 R-15-09 manual execution ([Simula] / [Executa ara]) of a process of the current club, audited as JOB_TRIGGERED. */
@Service
public class JobTriggerService {
    /** PRIV-02: how recent the confirmed dry run must be (a technical bound: the admin has just seen its counts). */
    static final Duration CONFIRMATION_WINDOW = Duration.ofMinutes(30);
    private final JobRunner runner; private final org.springframework.beans.factory.ObjectProvider<JobTriggerService> self;
    private final JobRunRepository runs; private final Clock clock;
    public JobTriggerService(JobRunner runner, org.springframework.beans.factory.ObjectProvider<JobTriggerService> self, JobRunRepository runs, Clock clock) {
        this.runner = runner; this.self = self; this.runs = runs; this.clock = clock;
    }

    /** A trigger without a confirmation: enough for a dry run and for every process but those of {@link Job#manualRunNeedsConfirmedDryRun}. */
    public JobViews.JobRunView trigger(JobName name, boolean dryRun) { return trigger(name, dryRun, null); }
    /**
     * Callers resolve `{name}` with {@link JobCatalog#byRoute}; the platform console opens the club scope first. A keyed request's
     * reference goes on the run (E5-T29 round 2): {@code JobAdminService} answers that run to the retry, without a second entry.
     * PRIV-02: a real run of a process that needs it is checked against its confirmation first ({@link #confirmedPlan}).
     */
    public JobViews.JobRunView trigger(JobName name, boolean dryRun, JobViews.JobRunConfirmation confirmation) {
        var confirmed = dryRun ? null : confirmedPlan(name, confirmation);
        // R-14-09 (INC-40, E11-T10): the run commits in the runner's own transactions; its entry is then written in one of its own.
        return self.getObject().audited(runner.manual(TenantContext.require(), name, dryRun, actor(), IdempotentOperation.reference(), confirmed));
    }

    /**
     * PRIV-02 (owner's decision of 13-11, S14 R-14-16, S15 R-15-09): a real manual run of a process that deletes personal data
     * (`retention-sweep`) names a dry run of the same process in this club, `SUCCEEDED` and started at most
     * {@link #CONFIRMATION_WINDOW} ago, and repeats the counts it showed. Its plan counts ({@link JobViews#plannedCounts}) are what
     * the runner compares again, under the lock, with a plan made at execution time. Any failure is
     * `409 SIMULATION_STALE {reason}`, before anything runs: `DRY_RUN_REQUIRED` (no confirmation, or no such dry run) or
     * `COUNTS_MISMATCH` (the counts are not the dry run's). Null for a process that needs none.
     */
    Map<String, Long> confirmedPlan(JobName name, JobViews.JobRunConfirmation confirmation) {
        var job = runner.registered(name).orElse(null);
        if (job == null || !job.manualRunNeedsConfirmedDryRun()) { return null; }
        if (confirmation == null) { throw stale("DRY_RUN_REQUIRED"); }
        var since = clock.instant().minus(CONFIRMATION_WINDOW);
        var dryRun = runs.forJob(name, confirmation.dryRunId())
                .filter(run -> run.dryRun() && run.status() == JobStatus.SUCCEEDED && !run.startedAt().isBefore(since))
                .orElseThrow(() -> stale("DRY_RUN_REQUIRED"));
        var shown = JobViews.plannedCounts(JobViews.forClub(dryRun).effects().counters());
        if (!shown.equals(JobViews.plannedCounts(confirmation.counters()))) { throw stale("COUNTS_MISMATCH"); }
        return shown;
    }
    private static ApiException stale(String reason) { return new ApiException(ErrorCode.SIMULATION_STALE, Map.of("reason", reason)); }

    /** The JOB_TRIGGERED entry of a run, in its own transaction (R-14-09); also for a run whose first attempt lost it (INC-50, E11-T02). */
    @org.springframework.transaction.annotation.Transactional
    @Audited(action = AuditAction.JOB_TRIGGERED, entityType = "'JobRun'", entity = "#result.runId()")
    public JobViews.JobRunView audited(com.agilityhub.core.platform.persistence.jobs.JobRun run) { return JobViews.view(run); }

    private static String actor() {
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) { return user.impersonation().actorAccountId(); }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    /** S14 R-14-09 / S15 R-15-09: `after {job, dryRun, status, counters}`. */
    public record TriggerSnapshot(@AuditField String job, @AuditField boolean dryRun, @AuditField JobStatus status,
            @AuditField Map<String, Long> counters) { }

    @org.springframework.stereotype.Component
    static class Loader implements AuditableLoader {
        private final JobRunRepository runs;
        Loader(JobRunRepository runs) { this.runs = runs; }
        @Override public String entityType() { return "JobRun"; }
        @Override public Object load(String entityId) {
            // The audit trail belongs to the club: no platform pass counters in it (AGENTS rule 4, JobViews.forClub).
            return runs.findById(entityId).map(JobViews::forClub)
                    .map(run -> new TriggerSnapshot(run.job(), run.dryRun(), run.status(), run.effects().counters())).orElse(null);
        }
    }
}
