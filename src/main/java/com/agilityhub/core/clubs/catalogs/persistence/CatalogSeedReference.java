package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** FAQ seed keys stay stable when questions, categories or display order change. */
@Document("catalog_seed_references")
public record CatalogSeedReference(@Id String id, String clubId, String entityId) implements TenantEntity { }
