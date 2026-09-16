package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.scheduling.domain.*;

@Document("class_sessions")
public record ClassSession(@Id String id, String clubId,
        String weekId, LocalDate date, String startTime, String endTime, Instant startsAt, Instant endsAt,
        String ringId, List<String> levelIds, List<String> instructorIds, int capacity, CapacityMode capacityMode,
        String description, ClassState state, Counters counters, Risk risk, Cancellation cancellation, Origin origin,
        String placementId, String notes,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {
    public record Counters(int booked, int waiting) { }
    public record Risk(boolean exempt, List<String> notifiedBookingIds, Instant adminNotifiedAt, Instant lowAlertSentAt) { }
    public record Cancellation(ClassCancellationReason reason, String adminText, String byAccountId, Instant at,
            int affectedBookings, int affectedWaitlist) { }
    public record Origin(String templateId, String templateClassId) { }
}
