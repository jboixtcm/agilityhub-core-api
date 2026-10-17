package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.jobs.Job;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobEffect;
import com.agilityhub.core.platform.application.jobs.JobItem;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S14 R-14-16 `RetentionSweep` through S15 (E11-T01, route `retention-sweep`, monthly on the 1st at `jobs.dailyTime`, traced by
 * its `JobRun` and `SchedulerRun`): (a) `LEFT` members with `leaveDate + rgpd.retentionYearsAfterLeave ≤ today` and (b, INC-10)
 * rejected signups with `leftAt + rgpd.rejectedSignupRetentionDays ≤ today` get an `ErasureRequest{source: RETENTION}` executed at
 * once (a blocked one stays `BLOCKED` for the console); (c) and (e) are the owning contexts' {@link RetentionTask}s; (d) audit
 * entries older than `audit.retentionYears` are purged. (f) security events expire through their TTL index (R-14-17).
 */
@Component
public class RetentionSweepJob implements Job {
    static final String PROCESS = "retention-sweep";
    private final ErasureDirectory directory;
    private final ErasureRequestRepository requests;
    private final ErasureService erasures;
    private final ErasureExecutor executor;
    private final AuditRepository audit;
    private final List<RetentionTask> tasks;
    public RetentionSweepJob(ErasureDirectory directory, ErasureRequestRepository requests, ErasureService erasures, ErasureExecutor executor,
            AuditRepository audit, List<RetentionTask> tasks) {
        this.directory = directory; this.requests = requests; this.erasures = erasures; this.executor = executor; this.audit = audit;
        this.tasks = List.copyOf(tasks);
    }
    @Override public JobName name() { return JobName.RETENTION_SWEEP; }

    @Override public List<JobItem> plan(JobContext context) {
        for (String counter : List.of("membersErased", "rejectedSignupsErased", "erasuresBlocked", "auditEntriesPurged")) { context.recorder().count(counter, 0); }
        tasks.forEach(task -> context.recorder().count(task.counter(), 0));
        int years = context.parameter("rgpd.retentionYearsAfterLeave", Integer.class);
        int rejectedDays = context.parameter("rgpd.rejectedSignupRetentionDays", Integer.class);
        int auditYears = context.parameter("audit.retentionYears", Integer.class);
        var items = new ArrayList<JobItem>();
        for (var member : directory.retentionDue(context.localDate().minusYears(years), context.scheduledFor().minus(Duration.ofDays(rejectedDays)))) {
            var live = requests.live(member.id());
            // A live request of another kind already carries this member (its own margin, or the console's); a blocked one is retried here.
            if (live.isPresent() && !"BLOCKED".equals(live.get().status())) { continue; }
            items.add(new JobItem("Member", member.id(), rejected(member) ? "ERASE_REJECTED_SIGNUP" : "ERASE_RETENTION"));
        }
        for (var task : tasks) { task.due(context).forEach(id -> items.add(new JobItem(task.entityType(), id, task.action()))); }
        Instant auditCutoff = context.localDate().minusYears(auditYears).atStartOfDay(context.zone()).toInstant();
        if (audit.countBefore(auditCutoff) > 0) { items.add(new JobItem("AuditEntry", auditCutoff.toString(), "PURGE_AUDIT")); }
        return items;
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        switch (item.action()) {
            case "ERASE_RETENTION", "ERASE_REJECTED_SIGNUP" -> {
                var member = directory.member(item.entityId()).orElse(null);
                if (member == null || member.erased()) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
                var request = requests.live(member.id()).orElseGet(() -> erasures.requestRetention(member, PROCESS));
                var outcome = executor.execute(request.id());
                if (outcome == ErasureExecutor.Outcome.BLOCKED) { return new JobEffect("BLOCKED", Map.of("requestId", request.id()), Map.of("erasuresBlocked", 1L)); }
                String counter = "ERASE_REJECTED_SIGNUP".equals(item.action()) ? "rejectedSignupsErased" : "membersErased";
                return new JobEffect(item.action(), Map.of("requestId", request.id()), Map.of(counter, outcome == ErasureExecutor.Outcome.SKIPPED ? 0L : 1L));
            }
            case "PURGE_AUDIT" -> {
                long purged = audit.purgeBefore(Instant.parse(item.entityId()));
                return new JobEffect("PURGE_AUDIT", Map.of("purged", purged), Map.of("auditEntriesPurged", purged));
            }
            default -> {
                for (var task : tasks) {
                    if (task.action().equals(item.action()) && task.entityType().equals(item.entityType())) {
                        boolean done = task.apply(context, item.entityId());
                        return new JobEffect(done ? item.action() : "NOT_IN_SCOPE", Map.of(), Map.of(task.counter(), done ? 1L : 0L));
                    }
                }
                return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of());
            }
        }
    }

    static boolean rejected(ErasureMember member) { return "SIGNUP_REJECTED".equals(member.leftReason()); }
}
