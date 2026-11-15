package com.agilityhub.core.clubs.statistics.persistence;

import com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("competition_imports")
public record CompetitionImportRecord(String id, String clubId, Source source, String sourceUrl, String season,
        Instant importedAt, long revision, List<SavedEntry> entries) implements TenantEntity { }
