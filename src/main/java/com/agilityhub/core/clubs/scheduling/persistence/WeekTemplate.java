package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.scheduling.domain.*;

@Document("week_templates")
public record WeekTemplate(@Id String id, String clubId,
        String name, TemplateKind kind, String notes, boolean active, List<TimeBand> timeBands, List<TemplateClass> classes,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {
    public record TimeBand(String id, String startTime, String endTime) { }
    public record TemplateClass(String id, String bandId, DayOfWeek dayOfWeek, List<String> instructorIds,
            String ringId, List<String> levelIds, int capacity, CapacityMode capacityMode, String description, String placementId) { }
}
