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
@Document("placements")
public record Placement(@Id String id,
        String clubId,
        String courseId,
        long courseVersion,
        String ringId,
        PlacementTransform transform,
        PlacementMode placementMode,
        double warningThresholdM,
        List<ResolvedObstacle> resolvedObstacles,
        List<PlacementWarning> warningsJson,
        boolean stale,
        PlacementStatus status,
        PlacementUse mode,
        Instant scheduledAt,
        List<String> grades,
        List<DogSize> sizes,
        String displayName,
        String name,
        String activityId,
        List<String> classSessionIds,
        String buildSheetFileKey,
        String createdByAccountId,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String updatedByAccountId) implements TenantEntity { }
