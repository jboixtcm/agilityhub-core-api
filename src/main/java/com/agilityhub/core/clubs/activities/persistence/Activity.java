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
        com.agilityhub.core.shared.domain.LocalizedText title, ActivityType type,
        com.agilityhub.core.shared.domain.LocalizedText typeLabel, com.agilityhub.core.shared.domain.LocalizedText shortDescription,
        com.agilityhub.core.shared.domain.LocalizedText longDescription, Image image, List<ActivityDocument> documents,
        Location location, List<String> ringIds, LocalDate date, String startTime, String endTime, Instant startsAt, Instant endsAt,
        RingBlockWindow ringBlockWindow, LocalDate registrationFrom, LocalDate registrationTo,
        Instant registrationOpensAt, Instant registrationClosesAt, Integer minPlaces, Integer maxPlaces,
        List<String> levelIds, boolean waitlistEnabled, String visibility, List<Void> priceTiers, String slug,
        ActivityState state, Instant publishedAt, String publishedByAccountId, Instant finishedAt, Cancellation cancellation,
        Counters counters, String internalNotes, List<String> ringBlockIds,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {
    public record Image(String fileId, String fileKey, String name, String mimeType, long sizeBytes) { }
    public record ActivityDocument(String id, String fileKey, String name, String mimeType, long sizeBytes, Instant uploadedAt) { }
    public record Location(boolean atClub, String name, String address, String url) { }
    public record RingBlockWindow(String fromTime, String toTime) { }
    public record Cancellation(ActivityCancellationReason reason, String adminText, Instant at, String byAccountId, int affectedCount) { }
    public record Counters(int active, int waiting) { }
}
