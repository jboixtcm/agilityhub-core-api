package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * ownerAccountId retains compatibility with the E2-T07 durable handoff. `subjectMemberId` and `deliverToMember` are S14 §3's for a
 * `MEMBER_DATA` package (R-14-13): whose data it is, and whether the member gets N-50 (always for `/me`).
 */
@Document("export_jobs")
public record ExportJob(@Id String id, String clubId, String ownerAccountId, String kind,
        String listKey, String format, String status, List<String> columns, ListQuery query,
        String locale, Instant createdAt, String timeZone, String fileName, String clubName, String primaryColor,
        Long rows, Integer progressPct, String fileKey, Long sizeBytes, String contentType, Instant expiresAt,
        int attempts, String claimToken, Instant leaseUntil, String errorCode, List<String> fileKeys, Instant purgeAt,
        String subjectMemberId, Boolean deliverToMember) implements TenantEntity {
    /** A list or accounting export (E2-T07…E8-T03). */
    public ExportJob(String id, String clubId, String ownerAccountId, String kind, String listKey, String format, String status, List<String> columns,
            ListQuery query, String locale, Instant createdAt, String timeZone, String fileName, String clubName, String primaryColor, Long rows,
            Integer progressPct, String fileKey, Long sizeBytes, String contentType, Instant expiresAt, int attempts, String claimToken,
            Instant leaseUntil, String errorCode, List<String> fileKeys, Instant purgeAt) {
        this(id, clubId, ownerAccountId, kind, listKey, format, status, columns, query, locale, createdAt, timeZone, fileName, clubName, primaryColor,
                rows, progressPct, fileKey, sizeBytes, contentType, expiresAt, attempts, claimToken, leaseUntil, errorCode, fileKeys, purgeAt, null, null);
    }
}
