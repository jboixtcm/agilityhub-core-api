package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.domain.CatalogEntity;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("rings")
public record Ring(@Id String id, String clubId, String name, String shortName, String color, boolean allowsFreeTraining, Integer trainingCapacity, int order, boolean active, long version,
        Instant createdAt, Instant updatedAt, String createdByAccountId, String updatedByAccountId) implements CatalogEntity { }
