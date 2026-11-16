package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.clubs.census.application.CompetitionDogDirectory;
import com.agilityhub.core.clubs.statistics.persistence.CompetitionImportRepository;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionValidation.*;

@Service
public class CompetitionStatistics {
    private final CompetitionImportRepository imports; private final ClubClock clock; private final CompetitionDogDirectory dogs;
    public CompetitionStatistics(CompetitionImportRepository imports, ClubClock clock, CompetitionDogDirectory dogs) { this.imports = imports; this.clock = clock; this.dogs = dogs; }
    public Statistics read(String dogId, Integer requestedYear, String requestedSeason) {
        var today = clock.today(TenantContext.require());
        int year = requestedYear == null ? today.getYear() : requestedYear;
        if (year < 2000 || year > 2100) { throw invalid("INVALID_YEAR"); }
        int startYear = today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1;
        String season = requestedSeason == null ? startYear + "-" + (startYear + 1) : requestedSeason;
        if (!season.matches("20\\d{2}-20\\d{2}") || Integer.parseInt(season.substring(5)) != Integer.parseInt(season.substring(0, 4)) + 1) {
            throw invalid("INVALID_SEASON");
        }
        var results = imports.forDog(dogId).stream()
                .flatMap(source -> source.entries().stream().filter(entry -> dogId.equals(entry.dogId()))
                        .map(entry -> new Result(source.source(), source.sourceUrl(), source.importedAt(), entry.result(), source.organizingClubName()))).toList();
        var stats = aggregate(dogId, year, season, results);
        return new Statistics(stats.dogId(), year, season, stats.standings(), stats.scoredTrials(), stats.summaries(),
                stats.results(), RsceProgress.calculate(results, dogs.licenses(dogId), year, today), stats.lastImportedAt());
    }
    static Statistics aggregate(String dogId, int year, String season, List<Result> all) {
        // A result imported from two provider pages counts once. Newer corrections win deterministically.
        var unique = new LinkedHashMap<String, Result>();
        all.stream().sorted(Comparator.comparing(Result::importedAt).thenComparing(Result::sourceUrl)).forEach(row -> {
            var entry = row.result();
            boolean selected = row.source() == Source.FCAG ? season.equals(entry.season()) : entry.date() != null && entry.date().getYear() == year;
            if (selected) {
                String key = row.source() == Source.FCAG ? "FCAG|" + entry.division() + "|" + entry.category()
                        : "FLOW|" + entry.license() + "|" + entry.trialId() + "|" + entry.runId();
                unique.put(key, row);
            }
        });
        var standings = unique.values().stream().filter(row -> row.source() == Source.FCAG).toList();
        var results = unique.values().stream().filter(row -> row.source() == Source.FLOW_AGILITY)
                .sorted(Comparator.<Result, java.time.LocalDate>comparing(row -> row.result().date()).reversed()
                        .thenComparing(row -> row.result().trialId()).thenComparing(row -> row.result().runId())).toList();
        int trials = (int) results.stream().filter(row -> row.result().point() != Point.NONE).map(row -> row.result().trialId()).distinct().count();
        var summaries = summarize(results.stream().map(Result::result).toList());
        Instant latest = unique.values().stream().map(Result::importedAt).max(Comparator.naturalOrder()).orElse(null);
        return new Statistics(dogId, year, season, standings, trials, summaries, results, List.of(), latest);
    }
    static List<RunSummary> summarize(List<Entry> entries) {
        return Arrays.stream(Discipline.values()).map(discipline -> {
            var runs = entries.stream().filter(row -> row.discipline() == discipline).toList();
            var speeds = runs.stream().filter(CompetitionStatistics::completed).map(Entry::speedMps).filter(Objects::nonNull).toList();
            BigDecimal mean = speeds.isEmpty() ? null : speeds.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(speeds.size()), 2, RoundingMode.HALF_UP);
            int clears = (int) runs.stream().filter(CompetitionStatistics::clear).count();
            int eliminated = (int) runs.stream().filter(CompetitionStatistics::eliminated).count();
            int outcomes = (int) runs.stream().filter(CompetitionStatistics::knownOutcome).count();
            return new RunSummary(discipline, runs.size(), clears,
                    count(runs, Point.P), count(runs, Point.PA), count(runs, Point.PV), eliminated, outcomes, speeds.size(),
                    percentage(clears, outcomes), percentage(eliminated, outcomes), mean);
        }).toList();
    }
    static boolean hasOfficialPoint(Entry row) { return row.point() != null && row.point() != Point.NONE; }
    static String qualification(Entry row) { return Objects.toString(row.qualification(), "").strip().toUpperCase(Locale.ROOT); }
    static boolean clear(Entry row) {
        // Explicit official awards and EXC_0 confirm a clear even when Flow omits numerical penalties.
        return hasOfficialPoint(row) || qualification(row).equals("EXC_0")
                || completed(row) && row.totalPenalty() != null && row.totalPenalty().signum() == 0;
    }
    static boolean eliminated(Entry row) {
        return !hasOfficialPoint(row) && Set.of("DISQ", "DQ", "ELIM", "ELIM.", "ELIMINADO", "ELIMINADA", "ELIMINATED", "ELIMINAT", "DISQUALIFIED")
                .contains(qualification(row));
    }
    static boolean completed(Entry row) {
        return !eliminated(row) && (hasOfficialPoint(row) || qualification(row).equals("EXC_0")
                || row.timeSeconds() != null && row.timeSeconds().signum() > 0 && row.position() != null);
    }
    static boolean knownOutcome(Entry row) {
        return clear(row) || eliminated(row) || completed(row) && (row.totalPenalty() != null
                || row.faults() != null && row.faults() > 0 || row.refusals() != null && row.refusals() > 0);
    }
    private static BigDecimal percentage(int value, int samples) {
        return samples == 0 ? null : BigDecimal.valueOf(value * 100L).divide(BigDecimal.valueOf(samples), 1, RoundingMode.HALF_UP);
    }
    private static int count(List<Entry> rows, Point point) { return (int) rows.stream().filter(row -> row.point() == point || row.point() == Point.PA_PV && (point == Point.PA || point == Point.PV)).count(); }
}
