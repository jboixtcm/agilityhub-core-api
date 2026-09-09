package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("attachments")
public record Attachment(@Id String id, String clubId, String entityType, String entityId, String fileKey, String name,
        String mimeType, long sizeBytes, String uploadedByAccountId, Instant createdAt, Instant updatedAt,
        Instant removedAt, String removedByAccountId, long version) implements TenantEntity { }
