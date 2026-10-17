package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.ExportJob;
import com.agilityhub.core.clubs.common.persistence.ListExportRepository;
import com.agilityhub.core.clubs.common.persistence.SavedViewRepository;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.PrivacyFileStore;
import com.agilityhub.core.platform.application.privacy.RetentionTask;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, `clubs.common`: R-14-15's saved views of an erased account (technical) and the exports it owns or that package an
 * erased member's data (tombstoned, files deleted after the commit); the exports store of `filesPending[]` (`exports:`); and
 * R-14-16 (e), the expired export jobs (`EXPIRED`, file deleted), which the export worker also sweeps every second.
 */
@Component
public class CommonErasure implements ErasureStep, PrivacyFileStore, RetentionTask {
    public static final String STORE = "exports";
    private final SavedViewRepository views;
    private final ListExportRepository jobs;
    private final ExportStorage storage;
    public CommonErasure(SavedViewRepository views, ListExportRepository jobs, ExportStorage storage) { this.views = views; this.jobs = jobs; this.storage = storage; }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        effect.count("saved_views", views.deleteOwnedBy(subject.erasedAccountIds()));
        jobs.eraseFor(subject.erasedAccountIds(), subject.memberIds(), subject.at()).forEach(key -> effect.file(STORE, key));
        return effect.build();
    }

    @Override public String store() { return STORE; }
    @Override public InputStream read(String key) { return storage.open(key); }
    @Override public void delete(String key) { storage.delete(key); }

    @Override public String entityType() { return "ExportJob"; }
    @Override public String action() { return "EXPIRE_EXPORT"; }
    @Override public String counter() { return "exportsExpired"; }
    @Override public List<String> due(JobContext context) { return jobs.expired(context.scheduledFor()).stream().map(ExportJob::id).toList(); }
    @Override public boolean apply(JobContext context, String jobId) {
        var job = jobs.findById(jobId).filter(found -> !"EXPIRED".equals(found.status())).orElse(null);
        if (job == null) { return false; }
        var keys = new HashSet<String>();
        if (job.fileKeys() != null) { keys.addAll(job.fileKeys()); }
        if (job.fileKey() != null) { keys.add(job.fileKey()); }
        keys.forEach(storage::delete);
        jobs.cleaned(job, context.scheduledFor());
        return true;
    }
}
