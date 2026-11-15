package com.agilityhub.core.clubs.statistics.persistence;

import com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("competition_previews")
public record CompetitionPreviewRecord(String id, String clubId, Parsed parsed, String sourceId, long sourceRevision,
        Instant expiresAt, Receipt receipt, String assignmentsHash, String replayAccountId, String replayKey) implements TenantEntity { }
