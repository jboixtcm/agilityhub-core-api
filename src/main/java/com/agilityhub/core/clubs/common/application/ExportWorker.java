package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExportWorker {
    private final ExportWorkRepository work; private final ListExportRepository jobs;
    private final ExportJobCoordinator coordinator; private final ExportJobRenderer renderer; private final ExportStorage storage; private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExportWorker.class);
    public ExportWorker(ExportWorkRepository work, ListExportRepository jobs, ExportJobCoordinator coordinator, ExportJobRenderer renderer, ExportStorage storage, Clock clock) {
        this.work = work; this.jobs = jobs; this.coordinator = coordinator; this.renderer = renderer; this.storage = storage; this.clock = clock;
    }
    @Scheduled(fixedDelay = 1000, scheduler = "exportScheduler")
    public void poll() {
        if (!busy.compareAndSet(false, true)) { return; }
        try {
            for (String club : work.clubs(clock.instant())) {
                try (var tenant = TenantContext.open(club)) {
                    cleanup();
                    ExportJob job = coordinator.claim();
                    if (job != null) {
                        try { renderer.render(job, false); }
                        catch (RuntimeException ex) { coordinator.fail(job, ex); LOG.warn("Export attempt failed for job {} (attempt {})", job.id(), job.attempts()); }
                    }
                } catch (RuntimeException ex) { LOG.warn("Export poll failed for club {}", club); }
            }
        } finally { busy.set(false); }
    }
    public void cleanup() {
        for (ExportJob job : jobs.expired(clock.instant())) {
            // Keep the job until every external deletion succeeds; the next poll retries failures.
            var keys = new java.util.HashSet<String>();
            if (job.fileKeys() != null) { keys.addAll(job.fileKeys()); }
            if (job.fileKey() != null) { keys.add(job.fileKey()); }
            keys.forEach(storage::delete);
            jobs.cleaned(job, clock.instant());
        }
    }
}
