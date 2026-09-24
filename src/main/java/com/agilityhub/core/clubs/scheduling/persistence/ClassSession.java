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

@Document("class_sessions")
public record ClassSession(@Id String id, String clubId,
        String weekId, @ValueConverter(CalendarDateConverter.class) LocalDate date, @AuditField String startTime, @AuditField String endTime, Instant startsAt, Instant endsAt,
        @AuditField String ringId, @AuditField List<String> levelIds, @AuditField List<String> instructorIds, @AuditField int capacity, @AuditField CapacityMode capacityMode,
        @AuditField String description, @AuditField ClassState state, @AuditField Counters counters, @AuditField Risk risk, @AuditField Cancellation cancellation, Origin origin,
        String placementId, @AuditField String notes,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId, Instant finishedAt,
        AttendanceSummary attendanceSummary) implements TenantEntity {
    /** A document written before S10 (no `attendanceSummary`) reads as the empty summary {version: 0, marked: 0, …}. */
    public ClassSession { if (attendanceSummary == null) { attendanceSummary = AttendanceSummary.EMPTY; } }
    public ClassSession(String id,String clubId,String weekId,LocalDate date,String startTime,String endTime,Instant startsAt,Instant endsAt,
            String ringId,List<String> levelIds,List<String> instructorIds,int capacity,CapacityMode capacityMode,String description,ClassState state,
            Counters counters,Risk risk,Cancellation cancellation,Origin origin,String placementId,String notes,Long version,Instant createdAt,
            String createdByAccountId,Instant updatedAt,String updatedByAccountId) {
        this(id,clubId,weekId,date,startTime,endTime,startsAt,endsAt,ringId,levelIds,instructorIds,capacity,capacityMode,description,state,counters,risk,
                cancellation,origin,placementId,notes,version,createdAt,createdByAccountId,updatedAt,updatedByAccountId,null);
    }
    public ClassSession(String id,String clubId,String weekId,LocalDate date,String startTime,String endTime,Instant startsAt,Instant endsAt,
            String ringId,List<String> levelIds,List<String> instructorIds,int capacity,CapacityMode capacityMode,String description,ClassState state,
            Counters counters,Risk risk,Cancellation cancellation,Origin origin,String placementId,String notes,Long version,Instant createdAt,
            String createdByAccountId,Instant updatedAt,String updatedByAccountId,Instant finishedAt) {
        this(id,clubId,weekId,date,startTime,endTime,startsAt,endsAt,ringId,levelIds,instructorIds,capacity,capacityMode,description,state,counters,risk,
                cancellation,origin,placementId,notes,version,createdAt,createdByAccountId,updatedAt,updatedByAccountId,finishedAt,null);
    }
    /**
     * S10 §3: written only by S10 inside the attendance save (R-10-04); `version` is the optimistic lock of the sheet.
     * Not an {@code @AuditField}: it is not a planning change, and S06 copies it untouched on every edit.
     */
    public record AttendanceSummary(int version, int marked, int present, int notified, int noShow, Instant savedAt, String savedByName) {
        public static final AttendanceSummary EMPTY = new AttendanceSummary(0, 0, 0, 0, 0, null, null);
    }
    public record Counters(@AuditField int booked, @AuditField int waiting) { }
    public record Risk(@AuditField boolean exempt, List<String> notifiedBookingIds, Instant adminNotifiedAt, Instant lowAlertSentAt) { }
    public record Cancellation(@AuditField ClassCancellationReason reason, @AuditField String adminText, @AuditField String byAccountId, @AuditField Instant at,
            @AuditField int affectedBookings, @AuditField int affectedWaitlist) { }
    public record Origin(String templateId, String templateClassId) { }
}
