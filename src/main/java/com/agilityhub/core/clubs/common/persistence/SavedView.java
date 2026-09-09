package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.clubs.common.application.SavedViewData;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("saved_views")
public record SavedView(@Id String id, String clubId, SavedViewData data, Instant createdAt, Instant updatedAt) implements TenantEntity { }
