package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.migration.domain.InputManifest;
import com.agilityhub.core.migration.domain.MigrationReport;
import com.agilityhub.core.migration.domain.MigrationExecution;
import java.time.Instant;
import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("migration_runs")
public record MigrationRun(@Id String id,String clubId,String source,String mode,String env,int mappingVersion,
        String status,Instant startedAt,Instant finishedAt,Map<String,Long> counters,
        InputManifest inputManifest, List<MigrationReport.Entry> reportRows, String reportFileKey,
        Instant reportExpiresAt, Instant reportPurgedAt, MigrationExecution execution) implements TenantEntity {
    public MigrationRun(String id, String clubId, String source, String mode, String env, int mappingVersion,
            String status, Instant startedAt, Instant finishedAt, Map<String, Long> counters) {
        this(id,clubId,source,mode,env,mappingVersion,status,startedAt,finishedAt,counters,null,List.of(),null,null,null,null);
    }
    public MigrationRun(String id, String clubId, String source, String mode, String env, int mappingVersion,
            String status, Instant startedAt, Instant finishedAt, Map<String,Long> counters, InputManifest inputManifest,
            List<MigrationReport.Entry> reportRows, String reportFileKey, Instant reportExpiresAt, Instant reportPurgedAt) {
        this(id,clubId,source,mode,env,mappingVersion,status,startedAt,finishedAt,counters,inputManifest,reportRows,reportFileKey,reportExpiresAt,reportPurgedAt,null);
    }
    public MigrationRun { reportRows = reportRows == null ? List.of() : List.copyOf(reportRows); }
    public MigrationRun finished(String state, Instant at, Map<String,Long> totals, List<MigrationReport.Entry> rows) {
        return new MigrationRun(id,clubId,source,mode,env,mappingVersion,state,startedAt,at,totals,inputManifest,rows,
                reportFileKey,reportExpiresAt!=null ? reportExpiresAt : "PRODUCTION".equals(env) ? at.plus(java.time.Duration.ofDays(30)) : null,reportPurgedAt,execution);
    }
}
