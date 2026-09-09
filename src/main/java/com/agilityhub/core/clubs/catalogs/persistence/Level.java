package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.domain.CatalogEntity;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("levels")
public record Level(@Id String id, String clubId, String code, LocalizedText name, int order, String color, int capacity, boolean grantsFreeTraining, boolean active, long version,
        Instant createdAt, Instant updatedAt, String createdByAccountId, String updatedByAccountId) implements CatalogEntity { }
