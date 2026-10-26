package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.MigrationReportRetention;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MigrationReports implements MigrationReportRetention {
    private final MigrationRunRepository runs; private final MigrationReportStore store;
    private final MigrationReportWorkbook workbooks; private final ClubConfigService configs; private final Clock clock;
    private final AuditWriter audit;
    public MigrationReports(MigrationRunRepository runs,MigrationReportStore store,MigrationReportWorkbook workbooks,ClubConfigService configs,Clock clock,AuditWriter audit) {
        this.runs=runs; this.store=store; this.workbooks=workbooks; this.configs=configs; this.clock=clock;
        this.audit=audit;
    }
    public void requireReady() {
        if (!Integer.valueOf(30).equals(configs.current(TenantContext.require()).get("migration.exportRetentionDays",Integer.class))) {
            throw new IllegalStateException("S18 migration report retention supports only migration.exportRetentionDays=30");
        }
        store.requireReady();
    }
    public void publish(String id,PlayoffInput input) {
        var run=runs.findById(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        if (!"PRODUCTION".equals(run.env()) || run.finishedAt()==null || run.inputManifest()==null
                || !Set.of("COMPLETED","RECONCILED","FAILED").contains(run.status())) { throw new ApiException(ErrorCode.INVALID_STATE); }
        active(run);
        if (!run.inputManifest().equals(input.manifest())) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
        requireReady();
        var file=workbooks.write(run,input);
        RuntimeException publicationFailure=null;
        try {
            store.put(key(run),file,run.reportExpiresAt());
            if (!runs.reportPublished(id,key(run),clock.instant())) {
                var current=runs.findById(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
                active(current);
                if (!key(run).equals(current.reportFileKey())) { throw new ApiException(ErrorCode.INVALID_STATE); }
            }
        } catch(RuntimeException failure) { publicationFailure=failure; throw failure; }
        finally {
            try { Files.deleteIfExists(file); }
            catch(IOException cleanup) {
                if (publicationFailure!=null) { publicationFailure.addSuppressed(cleanup); }
                else { throw new UncheckedIOException(cleanup); }
            }
        }
    }
    public record ReportLink(String downloadUrl,Instant expiresAt) { }
    public ReportLink download(String id) {
        var run=runs.findById(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        if (!"PRODUCTION".equals(run.env()) || run.reportExpiresAt()==null
                || run.reportPurgedAt()==null && !key(run).equals(run.reportFileKey())) { throw new ApiException(ErrorCode.NOT_FOUND); }
        active(run);
        Instant expires=clock.instant().plus(Duration.ofMinutes(5));
        if (expires.isAfter(run.reportExpiresAt())) { expires=run.reportExpiresAt(); }
        String url=store.downloadUrl(run.reportFileKey(),expires);
        audit.write(new AuditCommand(AuditAction.DATA_EXPORTED,"MigrationRun",id,null,null,null,null),Map.of("file","report.xlsx"));
        return new ReportLink(url,expires);
    }
    /** Self-authorising file route: the signature selects the club, then the persisted run enforces its retention. */
    public InputStream openVolume(String clubId, String id, long expires, String signature) {
        if (!(store instanceof VolumeMigrationReportStore volume)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        String key = "migration-reports/" + clubId + "/" + id + "/report.xlsx";
        volume.authorize(key, expires, signature);
        try (var scope = TenantContext.open(clubId)) {
            var run = runs.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!"PRODUCTION".equals(run.env()) || !key.equals(run.reportFileKey())) { throw new ApiException(ErrorCode.NOT_FOUND); }
            active(run);
            if (Instant.ofEpochSecond(expires).isAfter(run.reportExpiresAt())) { throw new ApiException(ErrorCode.FORBIDDEN); }
            return volume.open(key);
        }
    }
    @Override public List<String> expired(Instant at) { return runs.expiredReports(at).stream().map(MigrationRun::id).toList(); }
    @Override public boolean purge(String id,Instant at) {
        var run=runs.findById(id).orElse(null);
        if (run==null || run.reportExpiresAt()==null || run.reportExpiresAt().isAfter(at) || run.reportPurgedAt()!=null) { return false; }
        store.delete(key(run));
        return runs.reportPurged(id,at);
    }
    private void active(MigrationRun run) {
        if (run.reportExpiresAt()==null || !clock.instant().isBefore(run.reportExpiresAt()) || run.reportPurgedAt()!=null) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
    }
    private String key(MigrationRun run) { return "migration-reports/"+run.clubId()+"/"+run.id()+"/report.xlsx"; }
}
