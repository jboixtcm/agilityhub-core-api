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
    public enum Point { NONE, P, PA, PV }
    @Schema(name = "CompetitionEntry")
    public record Entry(String key, String license, String dogName, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String handlerName, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String clubName,
            String season, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String division, String category, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer position, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal points,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String trialId, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String runId, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) LocalDate date, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String trial, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String grade, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Discipline discipline,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer faults, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer refusals, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal totalPenalty, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal timeSeconds, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal speedMps,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String qualification, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Point point) { }
    @Schema(name = "CompetitionParsed")
    public record Parsed(Source source, String sourceUrl, String season, List<Entry> entries) { }
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
    public record Result(Source source, String sourceUrl, Instant importedAt, Entry result) { }
    @Schema(name = "CompetitionRunSummary")
    public record RunSummary(Discipline discipline, int runs, int clearRuns, int p, int pa, int pv,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal averageSpeedMps) { }
    @Schema(name = "CompetitionStatistics")
    public record Statistics(String dogId, int year, String season, List<Result> standings,
            int scoredTrials, List<RunSummary> summaries, List<Result> results, @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) Instant lastImportedAt) { }
}
