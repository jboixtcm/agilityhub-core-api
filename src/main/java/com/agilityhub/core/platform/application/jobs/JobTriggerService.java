package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.application.audit.AuditableLoader;
import com.agilityhub.core.platform.persistence.jobs.JobRunRepository;
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
    private final JobRunner runner; private final org.springframework.beans.factory.ObjectProvider<JobTriggerService> self;
    public JobTriggerService(JobRunner runner, org.springframework.beans.factory.ObjectProvider<JobTriggerService> self) { this.runner = runner; this.self = self; }

    /**
     * Callers resolve `{name}` with {@link JobCatalog#byRoute}; the platform console opens the club scope first. A keyed request's
     * reference goes on the run (E5-T29 round 2): {@code JobAdminService} answers that run to the retry, without a second entry.
     */
    public JobViews.JobRunView trigger(JobName name, boolean dryRun) {
        // R-14-09 (INC-40, E11-T10): the run commits in the runner's own transactions; its entry is then written in one of its own.
        return self.getObject().audited(runner.manual(TenantContext.require(), name, dryRun, actor(), IdempotentOperation.reference()));
    }

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
