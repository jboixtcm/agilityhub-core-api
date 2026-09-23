package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.convert.ValueConverter;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.scheduling.domain.*;

@Document("weeks")
public record Week(@Id String id, String clubId,
        int isoYear, int isoWeek, @ValueConverter(CalendarDateConverter.class) LocalDate startDate, @ValueConverter(CalendarDateConverter.class) LocalDate endDate, @AuditField WeekState state, Instant generatedAt, String generatedByAccountId, String weekdayTemplateId, String saturdayTemplateId, @AuditField Instant validatedAt, @AuditField String validatedByAccountId,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId,
        Instant openedAt, Instant openingNotifiedAt) implements TenantEntity {
    /** S15 §3 marks of P1 (week opening and N-33) default to unset. */
    public Week(String id, String clubId, int isoYear, int isoWeek, LocalDate startDate, LocalDate endDate, WeekState state, Instant generatedAt,
            String generatedByAccountId, String weekdayTemplateId, String saturdayTemplateId, Instant validatedAt, String validatedByAccountId,
            Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) {
        this(id, clubId, isoYear, isoWeek, startDate, endDate, state, generatedAt, generatedByAccountId, weekdayTemplateId, saturdayTemplateId,
                validatedAt, validatedByAccountId, version, createdAt, createdByAccountId, updatedAt, updatedByAccountId, null, null);
    }
}
