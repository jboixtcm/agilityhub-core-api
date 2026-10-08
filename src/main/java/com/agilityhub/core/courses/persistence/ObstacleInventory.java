package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** S16 §3/§14.2 storage; no HTTP endpoint serializes this document. */
@Document("obstacle_inventories")
public record ObstacleInventory(@Id String id,
        String clubId,
        InventoryScope scope,
        String ringId,
        List<InventoryItem> items,
        Instant updatedAt,
        Instant createdAt,
        String createdByAccountId,
        String updatedByAccountId) implements TenantEntity { }
