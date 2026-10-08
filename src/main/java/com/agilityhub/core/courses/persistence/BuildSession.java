package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.Document;

/** S16 §3/§14.2 storage; no HTTP endpoint serializes this document. */
@Document("build_sessions")
public record BuildSession(@Id String id,
        String clubId,
        String placementId,
        String ringId,
        BuildStatus status,
        BuildStrategy selectedStrategy,
        boolean live,
        String startedByAccountId,
        Instant startedAt,
        Instant finishedAt,
        String lastKnownMarkerId,
        long localCacheVersion,
        @Field(write = Field.Write.ALWAYS)
        @ValueConverter(CourseJsonConverter.class) JsonNode progressJson,
        String joinCode,
        String joinToken,
        Instant joinTokenRotatedAt,
        List<BuildObstacle> obstacles,
        List<BuildParticipant> participants,
        String buildSheetFileKey,
        long version,
        Instant createdAt,
        String createdByAccountId,
        Instant updatedAt,
        String updatedByAccountId) implements TenantEntity { }
