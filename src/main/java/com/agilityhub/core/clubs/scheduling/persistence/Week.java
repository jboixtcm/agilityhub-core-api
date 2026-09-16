package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.scheduling.domain.*;

@Document("weeks")
public record Week(@Id String id, String clubId,
        int isoYear, int isoWeek, LocalDate startDate, LocalDate endDate, WeekState state, Instant generatedAt, String generatedByAccountId, String weekdayTemplateId, String saturdayTemplateId, Instant validatedAt, String validatedByAccountId,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {

}
