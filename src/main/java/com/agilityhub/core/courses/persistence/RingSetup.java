package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Document;

/** S16 §3/§14.2 storage; no HTTP endpoint serializes this document. */
@Document("ring_setups")
public record RingSetup(@Id String id,
        String clubId,
        String ringId,
        SetupKind kind,
        String placementId,
        String courseId,
        String imageFileKey,
        AgilityHubLevel agilityhubLevel,
        List<String> levelIds,
        Instant builtAt,
        String builtByAccountId,
        @ValueConverter(CourseDateConverter.class) LocalDate expectedUntil,
        Instant expiresAt,
        SetupStatus status,
        Instant dismantledAt,
        String dismantledByAccountId,
        String notes,
        Instant createdAt,
        String createdByAccountId,
        Instant updatedAt,
        String updatedByAccountId) implements TenantEntity { }
