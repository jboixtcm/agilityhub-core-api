package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable E2-T08 handoff. No worker or download lifecycle is implemented in E2-T07. */
@Document("export_jobs")
public record QueuedListExport(@Id String id, String clubId, String ownerAccountId, String kind,
        String listKey, String format, String status, List<String> columns, ListQuery query,
        String locale, Instant createdAt) implements TenantEntity { }
