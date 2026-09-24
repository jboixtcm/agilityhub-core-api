package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.domain.CatalogEntity;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S05 §3 level. `progression` (E29): part of the progression of levels, the only ones that count for the automatic
 * «{first} i sup.» (S06 R-06-03); a level stored before the field existed reads as `true`.
 */
@Document("levels")
public record Level(@Id String id, String clubId, String code, LocalizedText name, int order, String color, int capacity, boolean grantsFreeTraining,
        Boolean progression, boolean active, long version, Instant createdAt, Instant updatedAt, String createdByAccountId, String updatedByAccountId)
        implements CatalogEntity {
    public Level { progression = progression == null || progression; }
}
