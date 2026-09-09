package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("attachment_uploads")
public record UploadGrant(@Id String id, String clubId, String accountId, String purpose, String fileName, String mimeType,
        long sizeBytes, Instant createdAt, Instant expiresAt, String boundEntity) implements TenantEntity { }
