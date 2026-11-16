package com.agilityhub.core.clubs.statistics.application;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Provider values remain explicit: a clear round never implies an official RSCE point. */
public final class CompetitionModels {
    private CompetitionModels() { }
    @Schema(name = "CompetitionSource")
    public enum Source { FCAG, FLOW_AGILITY }
    @Schema(name = "CompetitionDiscipline")
    public enum Discipline { AGILITY, JUMPING }
    @Schema(name = "CompetitionPoint")
    public enum Point { NONE, P, PA, PV, @com.fasterxml.jackson.annotation.JsonProperty("PA / PV") PA_PV }
    @Schema(name = "CompetitionEntry")
    public record Entry(String key, String license, String dogName, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String handlerName, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String clubName,
            String season, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String division, String category, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer position, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal points,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String trialId, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String runId, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) LocalDate date, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String trial, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String grade, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Discipline discipline,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer faults, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer refusals, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal totalPenalty, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal timeSeconds, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal speedMps,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String qualification, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Point point) { }
    @Schema(name = "CompetitionParsed")
    public record Parsed(Source source, String sourceUrl, String season, List<Entry> entries, String organizingClubName) {
        public Parsed(Source source, String sourceUrl, String season, List<Entry> entries) {
            this(source, sourceUrl, season, entries, null);
        }
    }
    @Schema(name = "CompetitionDogOption")
    public record DogOption(String id, String name, String license) { }
    @Schema(name = "CompetitionPreviewRow")
    public record PreviewRow(Entry entry, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String suggestedDogId, String match) { }
    @Schema(name = "CompetitionPreview")
    public record Preview(String id, Source source, String sourceUrl, String season, Instant expiresAt,
            List<PreviewRow> rows, List<DogOption> dogs) { }
    @Schema(name = "CompetitionImportRequest")
    public record ImportRequest(Source source, String sourceUrl, @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String content) { }
    @Schema(name = "CompetitionConfirmation")
    public record Confirmation(Map<String, String> assignments) { }
    @Schema(name = "CompetitionReceipt")
    public record Receipt(String id, Source source, String sourceUrl, String season, Instant importedAt,
            int importedRows, long revision) { }
    @Schema(name = "CompetitionSavedEntry")
    public record SavedEntry(String dogId, Entry result) { }
    @Schema(name = "CompetitionResult")
    public record Result(Source source, String sourceUrl, Instant importedAt, Entry result,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String organizingClubName) {
        public Result(Source source, String sourceUrl, Instant importedAt, Entry result) {
            this(source, sourceUrl, importedAt, result, null);
        }
    }
    @Schema(name = "CompetitionRunSummary")
    public record RunSummary(Discipline discipline, int runs, int clearRuns, int p, int pa, int pv, int eliminatedRuns, int outcomeSamples, int speedSamples,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal clearRatePercent,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal eliminatedRatePercent,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal averageSpeedMps) { }
    @Schema(name = "CompetitionAnnualSummary")
    public record AnnualSummary(int year,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) LocalDate firstResultDate,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) LocalDate lastResultDate,
            List<String> grades, List<RunSummary> summaries) { }
    @Schema(name = "RscePointTarget")
    public record PointTarget(Discipline discipline, String kind, int earned, int required, int remaining) { }
    @Schema(name = "RsceProgressRoute")
    public record ProgressRoute(String code, String targetGrade, int championshipYear, List<PointTarget> targets,
            boolean pointTargetsMet, boolean requiresDifferentJudges) { }
    @Schema(name = "RsceLicenseProgress")
    public record LicenseProgress(String license,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String currentGrade,
            String gradeSource,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String registeredGrade,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String lastResultGrade,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) LocalDate lastResultDate,
            boolean gradeConflict, boolean previousHigherGrade,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) LocalDate historyFrom,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) LocalDate historyThrough,
            List<RunSummary> summaries, List<AnnualSummary> annualSummaries, List<ProgressRoute> promotionRoutes, List<ProgressRoute> championshipRoutes,
            String rulesVersion, String rulesUrl) { }
    @Schema(name = "CompetitionStatistics")
    public record Statistics(String dogId, int year, String season, List<Result> standings,
            int scoredTrials, List<RunSummary> summaries, List<Result> results, List<LicenseProgress> rsceProgress, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Instant lastImportedAt) { }
}
