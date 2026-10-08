package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.Document;

/** S16 §3/§14.2 storage; no HTTP endpoint serializes this document. */
@Document("courses")
public record Course(@Id String id,
        OwnerType ownerType,
        String clubId,
        String ownerAccountId,
        Visibility visibility,
        LocalizedText name,
        Discipline discipline,
        String designerName,
        @ValueConverter(CourseDateConverter.class) LocalDate designedOn,
        String eventName,
        AgilityHubLevel agilityhubLevel,
        List<String> levelIds,
        List<String> tags,
        String notes,
        CourseSource source,
        String sourceCourseId,
        String sourceFileKey,
        String imageFileKey,
        String thumbnailFileKey,
        @Field(write = Field.Write.ALWAYS)
        @ValueConverter(CourseJsonConverter.class) JsonNode normalizedJson,
        Integer schemaVersion,
        Double designLengthM,
        Double designWidthM,
        Double canvasWidth,
        Double canvasHeight,
        String origin,
        String courseGrade,
        String courseType,
        SizeCategory sizeCategory,
        CourseStats stats,
        long version,
        Instant createdAt,
        String createdByAccountId,
        Instant updatedAt,
        List<CourseHistory> history,
        Instant deletedAt,
        String updatedByAccountId) implements TenantEntity { }
