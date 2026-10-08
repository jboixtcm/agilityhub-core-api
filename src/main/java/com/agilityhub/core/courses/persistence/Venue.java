package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Document;

/** S16 §3/§14.2 storage; no HTTP endpoint serializes this document. */
@Document("venues")
public record Venue(@Id String id,
        String clubId,
        String slug,
        String name,
        String country,
        String city,
        String address,
        Surface surface,
        IndoorOutdoor indoorOutdoor,
        String contactEmail,
        String publicNotes,
        String privateNotes,
        VenueVisibility visibility,
        PartnerStatus partnerStatus,
        @ValueConverter(CourseDateConverter.class) LocalDate partnerSince,
        @ValueConverter(CourseDateConverter.class) LocalDate partnerUntil,
        PaperSize defaultPaperSize,
        Instant createdAt,
        String createdByAccountId,
        Instant updatedAt,
        String updatedByAccountId) implements TenantEntity { }
