package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import java.time.*;
import java.util.List;

public final class PlanningViews {
    private PlanningViews() { }
    public record WeekTemplateSummary(String id, String name, TemplateKind kind, boolean active,
            int classCount, int inconsistencyCount, Instant updatedAt) { }
    public record WeekTemplates(List<WeekTemplateSummary> items) { }
    public record WeekTemplate(String id, String name, TemplateKind kind, List<DayOfWeek> days,
            String notes, boolean active, long version,
            List<TimeBand> bands, List<TemplateClass> classes, List<Inconsistency> inconsistencies, boolean canGenerate) { }
    public record TimeBand(String id, String startTime,
            String endTime) { }
    public record TemplateClass(String id, String bandId, DayOfWeek dayOfWeek, List<String> instructorIds,
            String ringId, List<String> levelIds,
            int capacity, CapacityMode capacityMode, String description,
            String displayDescription, List<String> inconsistencyIds,
            String placementId) { }
    public record Inconsistency(String id, InconsistencyType type,
            DayOfWeek dayOfWeek, LocalDate date,
            String startTime, String bandId,
            String ringId, String instructorId,
            String levelId, List<String> itemIds, String message) { }
    public record Week(String id, int isoYear, int isoWeek, LocalDate startDate, LocalDate endDate, WeekState state,
            Instant generatedAt,
            String generatedByAccountId,
            String weekdayTemplateId,
            String saturdayTemplateId,
            Instant validatedAt,
            String validatedByAccountId, long version) { }
    public record GenerationCandidates(List<GenerationCandidate> items) { }
    public record GenerationCandidate(LocalDate startDate, LocalDate endDate, int isoYear, int isoWeek,
            String weekId, WeekState state, boolean proposed) { }
    public record GenerationResult(String weekId, int classCount, List<SkippedClasses> skipped) { }
    public record SkippedClasses(LocalDate date, SkipReason reason, int count) { }
    public enum SkipReason { HOLIDAY, PAST }
    public record Coverage(CoverageScope scope, CoverageThresholds thresholds, int activeDogWeeks, List<CoverageLevel> levels) { }
    public enum CoverageScope { TEMPLATE, WEEK }
    public enum CoverageStatus { OK, TIGHT, SHORT, EXPAND, NO_DOGS }
    public record CoverageThresholds(int ok, int tight, @com.fasterxml.jackson.annotation.JsonProperty("short") int shortThreshold) { }
    public record CoverageLevel(String levelId, String name, int maxSeats, double propSeats, int dogsTotal, int dogsActive,
            Integer maxRatioPct,
            Integer propRatioPct, CoverageStatus status,
            Integer booked) { }
}
