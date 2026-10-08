package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.Document;

/** S16 §3/§14.2 storage; no HTTP endpoint serializes this document. */
@Document("calibration_logs")
public record CalibrationLog(@Id String id,
        String clubId,
        String ringId,
        CalibrationOutcome outcome,
        double averageErrorCm,
        double maxErrorCm,
        int markerCount,
        String deviceModel,
        String osVersion,
        String appVersion,
        String arProvider,
        String notes,
        @Field(write = Field.Write.ALWAYS)
        @ValueConverter(CourseJsonConverter.class) JsonNode rawMetadata,
        String createdByAccountId,
        Instant createdAt,
        Instant updatedAt,
        String updatedByAccountId) implements TenantEntity { }
