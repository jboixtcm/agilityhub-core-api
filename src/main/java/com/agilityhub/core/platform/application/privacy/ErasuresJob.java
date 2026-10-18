package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.jobs.Job;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobEffect;
import com.agilityhub.core.platform.application.jobs.JobItem;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S14 R-14-15 through S15 (E11-T01, route `erasures`, daily at `jobs.dailyTime`): every request of the club whose `executeAt` has
 * come (`SCHEDULED`, or `BLOCKED` for the daily retry) is executed by {@link ErasureExecutor}; a request still `ERASING`, or any
 * request with files still pending (a failed cleanup, E11-T11), gets its files cleaned again. The dry run lists both.
 */
@Component
public class ErasuresJob implements Job {
    private final ErasureRequestRepository requests;
    private final ErasureExecutor executor;
    private final ErasureFilesCleanup cleanup;
    public ErasuresJob(ErasureRequestRepository requests, ErasureExecutor executor, ErasureFilesCleanup cleanup) {
        this.requests = requests; this.executor = executor; this.cleanup = cleanup;
    }
    @Override public JobName name() { return JobName.ERASURES; }
    @Override public List<JobItem> plan(JobContext context) {
        for (String counter : List.of("erased", "partial", "blocked", "skipped", "filesCleaned")) { context.recorder().count(counter, 0); }
        var items = new ArrayList<JobItem>();
        requests.due(context.clubId(), context.scheduledFor()).forEach(request -> items.add(new JobItem("ErasureRequest", request.id(), "ERASE")));
        requests.toClean(context.clubId()).forEach(request -> items.add(new JobItem("ErasureRequest", request.id(), "DELETE_FILES")));
        return items;
    }
    @Override public JobEffect apply(JobContext context, JobItem item) {
        if ("DELETE_FILES".equals(item.action())) {
            boolean closed = cleanup.clean(item.entityId());
            return new JobEffect(closed ? "DELETE_FILES" : "NOT_IN_SCOPE", Map.of(), Map.of("filesCleaned", closed ? 1L : 0L));
        }
        var outcome = executor.execute(item.entityId());
        return new JobEffect(outcome.name(), Map.of(), Map.of(outcome.name().toLowerCase(Locale.ROOT), 1L));
    }
}
