package com.agilityhub.core.clubs.activities.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.activities.domain.*;

@Document("activities")
public record Activity(@Id String id, String clubId,
        @com.agilityhub.core.shared.domain.audit.AuditField com.agilityhub.core.shared.domain.LocalizedText title, @com.agilityhub.core.shared.domain.audit.AuditField ActivityType type,
        @com.agilityhub.core.shared.domain.audit.AuditField com.agilityhub.core.shared.domain.LocalizedText typeLabel, @com.agilityhub.core.shared.domain.audit.AuditField com.agilityhub.core.shared.domain.LocalizedText shortDescription,
        @com.agilityhub.core.shared.domain.audit.AuditField com.agilityhub.core.shared.domain.LocalizedText longDescription, @com.agilityhub.core.shared.domain.audit.AuditField Image image, @com.agilityhub.core.shared.domain.audit.AuditField List<ActivityDocument> documents,
        @com.agilityhub.core.shared.domain.audit.AuditField Location location, @com.agilityhub.core.shared.domain.audit.AuditField List<String> ringIds, @org.springframework.data.convert.ValueConverter(ActivityDateConverter.class) @com.agilityhub.core.shared.domain.audit.AuditField LocalDate date, @com.agilityhub.core.shared.domain.audit.AuditField String startTime, @com.agilityhub.core.shared.domain.audit.AuditField String endTime, Instant startsAt, Instant endsAt,
        @com.agilityhub.core.shared.domain.audit.AuditField RingBlockWindow ringBlockWindow, @org.springframework.data.convert.ValueConverter(ActivityDateConverter.class) @com.agilityhub.core.shared.domain.audit.AuditField LocalDate registrationFrom, @org.springframework.data.convert.ValueConverter(ActivityDateConverter.class) @com.agilityhub.core.shared.domain.audit.AuditField LocalDate registrationTo,
        Instant registrationOpensAt, Instant registrationClosesAt, @com.agilityhub.core.shared.domain.audit.AuditField Integer minPlaces, @com.agilityhub.core.shared.domain.audit.AuditField Integer maxPlaces,
        @com.agilityhub.core.shared.domain.audit.AuditField List<String> levelIds, @com.agilityhub.core.shared.domain.audit.AuditField boolean waitlistEnabled, String visibility, List<Void> priceTiers, @com.agilityhub.core.shared.domain.audit.AuditField String slug,
        @com.agilityhub.core.shared.domain.audit.AuditField ActivityState state, Instant publishedAt, String publishedByAccountId, Instant finishedAt, @com.agilityhub.core.shared.domain.audit.AuditField Cancellation cancellation,
        Counters counters, @com.agilityhub.core.shared.domain.audit.AuditField String internalNotes, List<String> ringBlockIds,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {
    public record Image(String fileId, String fileKey, String name, String mimeType, long sizeBytes) { }
    public record ActivityDocument(String id, String fileKey, String name, String mimeType, long sizeBytes, Instant uploadedAt) { }
    public record Location(boolean atClub, String name, String address, String url) { }
    public record RingBlockWindow(String fromTime, String toTime) { }
    public record Cancellation(ActivityCancellationReason reason, String adminText, Instant at, String byAccountId, int affectedCount) { }
    public record Counters(int active, int waiting) { }
}
