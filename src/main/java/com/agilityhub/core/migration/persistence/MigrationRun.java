package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("migration_runs")
public record MigrationRun(@Id String id,String clubId,String source,String mode,String env,int mappingVersion,
        String status,Instant startedAt,Instant finishedAt,Map<String,Long> counters) implements TenantEntity { }
