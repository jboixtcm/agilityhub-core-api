package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** ownerAccountId retains compatibility with the E2-T07 durable handoff. */
@Document("export_jobs")
public record ExportJob(@Id String id, String clubId, String ownerAccountId, String kind,
        String listKey, String format, String status, List<String> columns, ListQuery query,
        String locale, Instant createdAt, String timeZone, String fileName, String clubName, String primaryColor,
        Long rows, Integer progressPct, String fileKey, Long sizeBytes, String contentType, Instant expiresAt,
        int attempts, String claimToken, Instant leaseUntil, String errorCode, List<String> fileKeys, Instant purgeAt) implements TenantEntity { }
