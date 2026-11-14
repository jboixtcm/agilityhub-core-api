package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("instructors")
public record Instructor(@Id String id, String clubId, String memberId, String shortName, String color,
                         boolean active, long version, Instant createdAt, Instant updatedAt,
                         String createdByAccountId, String updatedByAccountId, Boolean schedulingOnly) implements TenantEntity {
    public Instructor(String id, String clubId, String memberId, String shortName, String color, boolean active, long version,
            Instant createdAt, Instant updatedAt, String createdByAccountId, String updatedByAccountId) {
        this(id, clubId, memberId, shortName, color, active, version, createdAt, updatedAt, createdByAccountId, updatedByAccountId, false);
    }

    /** Older roster documents predate the scheduling-only profile. */
    public boolean isSupport() { return Boolean.TRUE.equals(schedulingOnly); }
}
