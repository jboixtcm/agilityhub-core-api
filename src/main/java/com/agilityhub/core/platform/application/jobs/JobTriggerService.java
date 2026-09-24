package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.application.audit.AuditableLoader;
import com.agilityhub.core.platform.persistence.jobs.JobRunRepository;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** S15 R-15-09 manual execution ([Simula] / [Executa ara]) of a process of the current club, audited as JOB_TRIGGERED. */
@Service
public class JobTriggerService {
    private final JobRunner runner;
    public JobTriggerService(JobRunner runner) { this.runner = runner; }

    /** Callers resolve `{name}` with {@link JobCatalog#byRoute}; the platform console opens the club scope first. */
    @Audited(action = AuditAction.JOB_TRIGGERED, entityType = "'JobRun'", entity = "#result.runId()")
    public JobViews.JobRunView trigger(JobName name, boolean dryRun) {
        return JobViews.view(runner.manual(TenantContext.require(), name, dryRun, actor()));
    }

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
