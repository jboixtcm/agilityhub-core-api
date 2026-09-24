package com.agilityhub.core.clubs.scheduling.api;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.*;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** S06 wire projections. Computed descriptions and inconsistencies are never persisted. */
public final class SchedulingContracts {
    private SchedulingContracts() { }
    public record WeekTemplateSummary(String id, String name, TemplateKind kind, boolean active,
            int classCount, int inconsistencyCount, Instant updatedAt) { }
    public record WeekTemplates(List<WeekTemplateSummary> items) { }
    public record WeekTemplate(String id, String name, TemplateKind kind, List<DayOfWeek> days,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes, boolean active, long version,
            List<TimeBand> bands, List<TemplateClass> classes, List<Inconsistency> inconsistencies, boolean canGenerate) { }
    public record TimeBand(String id, @Schema(pattern = "^\\d{2}:\\d{2}$") String startTime,
            @Schema(pattern = "^\\d{2}:\\d{2}$") String endTime) { }
    public record TemplateClass(String id, String bandId, DayOfWeek dayOfWeek, List<String> instructorIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId, List<String> levelIds,
            int capacity, CapacityMode capacityMode, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String description,
            String displayDescription, List<String> inconsistencyIds,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with COURSES") String placementId) { }
    public record Inconsistency(String id, InconsistencyType type,
            @Schema(requiredMode = NOT_REQUIRED) DayOfWeek dayOfWeek, @Schema(requiredMode = NOT_REQUIRED) LocalDate date,
            @Schema(requiredMode = NOT_REQUIRED) String startTime, @Schema(requiredMode = NOT_REQUIRED) String bandId,
            @Schema(requiredMode = NOT_REQUIRED) String ringId, @Schema(requiredMode = NOT_REQUIRED) String instructorId,
            @Schema(requiredMode = NOT_REQUIRED) String levelId, List<String> itemIds, String message) { }
    public record Week(String id, int isoYear, int isoWeek, LocalDate startDate, LocalDate endDate, WeekState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant generatedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String generatedByAccountId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String weekdayTemplateId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String saturdayTemplateId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant validatedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String validatedByAccountId, long version) { }
    public record WeekListItem(String id, int isoYear, int isoWeek, LocalDate startDate, LocalDate endDate, WeekState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant generatedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant validatedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String weekdayTemplateName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String saturdayTemplateName, ClassCounts classCounts) { }
    public record ClassCounts(int draft, int active, int cancelled) { }
    public record GenerationCandidates(List<GenerationCandidate> items) { }
    public record GenerationCandidate(LocalDate startDate, LocalDate endDate, int isoYear, int isoWeek,
            @Schema(requiredMode = NOT_REQUIRED) String weekId, WeekState state, boolean proposed) { }
    public record GenerationResult(String weekId, int classCount, List<SkippedClasses> skipped) { }
    public record SkippedClasses(LocalDate date, SkipReason reason, int count) { }
    public enum SkipReason { HOLIDAY, PAST }
    @Schema(name = "WeekValidationResult")
    public record ValidationResult(List<String> validatedClassIds) { }
    public record WeekCalendar(CalendarWeek week, List<String> rows, List<ClassSession> classes,
            List<RingBlock> ringBlocks, List<Inconsistency> inconsistencies, int draftCount, boolean canValidate) { }
    public record CalendarWeek(String id, int isoYear, int isoWeek, LocalDate startDate, LocalDate endDate, WeekState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant generatedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant validatedAt, RelativeWeek relative) { }
    public enum RelativeWeek { CURRENT, NEXT, OTHER }
    public enum CalendarFilter { ACTIVE, DRAFT, CANCELLED }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ClassSession(String id, String weekId, LocalDate date, String startTime, String endTime,
            Instant startsAt, Instant endsAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId,
            List<String> levelIds, List<String> instructorIds, int capacity, CapacityMode capacityMode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String description, String displayDescription,
            ClassState state, ClassCounters counters, boolean atRisk, boolean riskExempt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ClassCancellation cancellation,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ClassOrigin origin,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with COURSES") String placementId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "ADMIN only; omitted for INSTRUCTOR") String notes,
            long version, List<String> inconsistencyIds,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only in GET /weeks/{id}/calendar: S10 attendance status from attendanceSummary (NONE before T0, PENDING inside the window with marked < total, DONE, CLOSED after T1)")
            com.agilityhub.core.clubs.scheduling.application.ports.AttendanceStatusPort.AttendanceStatus attendanceStatus) { }
    public record ClassCounters(int booked, int waiting) { }
    public record ClassCancellation(ClassCancellationReason reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String adminText, String byAccountId, Instant at,
            int affectedBookings, int affectedWaitlist) { }
    public record ClassOrigin(@Schema(requiredMode = NOT_REQUIRED) String templateId,
            @Schema(requiredMode = NOT_REQUIRED) String templateClassId) { }
    public record ClassSessionMemberView(String id, LocalDate date, String startTime, String endTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ClassRing ring, List<String> levelIds, String displayDescription,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Visibility follows bookings.showInstructorHoursBefore") String instructorName,
            ClassState state, int capacity, int freeSeats, @Schema(requiredMode = NOT_REQUIRED, description = "Only with WAITLIST") Integer waiting) { }
    public record ClassRing(String id, String name, String color) { }
    public record CancellationPreview(List<CancellationBooking> bookings, int waitlistCount) { }
    public record CancellationBooking(String bookingId, String memberName, String dogName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelName, List<String> channels, int phoneCount) { }
    public record RingBlock(String id, String ringId, Instant from, Instant to, LocalDate date, String fromLocal, String toLocal,
            RingBlockKind kind, RingBlockReason reason, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String note,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityTitle,
            String createdByName, RingBlockState state, long version) { }
    public record RingBlockMemberView(String id, String ringId, Instant from, Instant to, LocalDate date, String fromLocal,
            String toLocal, RingBlockKind kind, RingBlockReason reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityTitle, RingBlockState state, long version) { }
    public record DayGrid(LocalDate date, DayOfWeek dayOfWeek, String timeZone, GridView view,
            List<DayGridColumn> columns, List<DayGridRow> rows) { }
    public enum GridView { MEMBER, INSTRUCTOR }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record DayGridColumn(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId, String shortName,
            String name, String color, @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with COURSES") String activeSetupId) { }
    public record DayGridRow(String time, List<DayGridCell> cells) { }
    public enum CellKind { CLASS, OCCUPIED, TRAINING, BLOCK, ACTIVITY }
    public enum CellReason { MAINTENANCE, PRIVATE_CLASS, THERAPY, PREPARATION, ACTIVITY, OTHER, TRAINING }
    @Schema(description = "MEMBER: CLASS/OCCUPIED/ACTIVITY only; no occupancy, who, trainingBookingIds, note, createdByName or blockId. Instructor name follows R-06-12. INSTRUCTOR/ADMIN: all cell kinds, counts and names. ACTIVITY carries activityId/title in both views.")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record DayGridCell(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId, CellKind kind, String endTime,
            @Schema(requiredMode = NOT_REQUIRED) String classId, @Schema(requiredMode = NOT_REQUIRED) String description,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String instructorName, @Schema(requiredMode = NOT_REQUIRED) ClassState state,
            @Schema(requiredMode = NOT_REQUIRED) Boolean atRisk, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String riskText,
            @Schema(requiredMode = NOT_REQUIRED) GridOccupancy occupancy, @Schema(requiredMode = NOT_REQUIRED) List<String> who,
            @Schema(requiredMode = NOT_REQUIRED) List<String> trainingBookingIds, @Schema(requiredMode = NOT_REQUIRED) String blockId,
            @Schema(requiredMode = NOT_REQUIRED) CellReason reason, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String note,
            @Schema(requiredMode = NOT_REQUIRED) String createdByName, @Schema(requiredMode = NOT_REQUIRED) String activityId,
            @Schema(requiredMode = NOT_REQUIRED) String title,
            @Schema(requiredMode = NOT_REQUIRED, description = "CLASS cells of the INSTRUCTOR view only (S10 §7)")
            com.agilityhub.core.clubs.scheduling.application.ports.AttendanceStatusPort.AttendanceStatus attendanceStatus) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record GridOccupancy(int booked, int capacity, @Schema(requiredMode = NOT_REQUIRED, description = "Only with WAITLIST") Integer waiting) { }
    public record Coverage(CoverageScope scope, CoverageThresholds thresholds, int activeDogWeeks, List<CoverageLevel> levels) { }
    public enum CoverageScope { TEMPLATE, WEEK }
    public enum CoverageStatus { OK, TIGHT, SHORT, EXPAND, NO_DOGS }
    public record CoverageThresholds(int ok, int tight, @JsonProperty("short") int shortThreshold) { }
    public record CoverageLevel(String levelId, String name, int maxSeats, double propSeats, int dogsTotal, int dogsActive,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer maxRatioPct,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer propRatioPct, CoverageStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only for scope WEEK") Integer booked) { }
}
