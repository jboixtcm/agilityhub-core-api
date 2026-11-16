package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.clubs.census.application.CompetitionDogDirectory.CompetitionLicense;
import java.time.LocalDate;
import java.util.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;

/** RSCE articles 60–63 and 71, consolidated 2026-07-01. Point progress, never official eligibility. */
final class RsceProgress {
    static final String VERSION = "RSCE-2026-07-01";
    static final String RULES_URL = "https://www.rsce.es/wp-content/uploads/2026/07/REGLAMENTO-AGILITY-1-01-26-EN-VIGOR-mayo.pdf";
    private RsceProgress() { }

    static List<LicenseProgress> calculate(List<Result> all, List<CompetitionLicense> licenses, int year, LocalDate today) {
        // Corrections of the same licensed pair/run replace the earlier snapshot, across every imported year.
        var unique = new LinkedHashMap<String, Entry>();
        all.stream().filter(row -> row.source() == Source.FLOW_AGILITY)
                .sorted(Comparator.comparing(Result::importedAt).thenComparing(Result::sourceUrl))
                .map(Result::result).filter(row -> row.date() != null && !row.date().isAfter(today))
                .forEach(row -> unique.put(row.license() + "|" + row.trialId() + "|" + row.runId(), row));
        var registered = new TreeMap<String, String>();
        licenses.forEach(license -> registered.put(license.number(), Objects.toString(grade(license.grade()), "")));
        unique.values().forEach(row -> registered.putIfAbsent(row.license(), ""));
        return registered.entrySet().stream().map(license -> {
            var history = unique.values().stream().filter(row -> license.getKey().equals(row.license()))
                    .sorted(Comparator.comparing(Entry::date).thenComparing(Entry::runId)).toList();
            String registeredGrade = grade(license.getValue());
            LocalDate lastDate = history.isEmpty() ? null : history.getLast().date();
            var lastGrades = history.stream().filter(row -> row.date().equals(lastDate)).map(Entry::grade)
                    .map(RsceProgress::grade).filter(Objects::nonNull).distinct().toList();
            String observedGrade = lastGrades.size() == 1 ? lastGrades.getFirst() : null;
            // The licence is authoritative when known. An observed grade is explicitly labelled as a fallback.
            String currentGrade = registeredGrade == null ? observedGrade : registeredGrade;
            String source = registeredGrade != null ? "LICENSE" : observedGrade != null ? "LAST_RESULT" : "UNKNOWN";
            boolean conflict = lastGrades.size() > 1 || registeredGrade != null && observedGrade != null && !registeredGrade.equals(observedGrade);
            var higher = history.stream().filter(row -> grade(row.grade()) != null && currentGrade != null
                    && grade(row.grade()).compareTo(currentGrade) > 0).map(Entry::date).max(Comparator.naturalOrder());
            var promotionHistory = history.stream().filter(row -> currentGrade != null && currentGrade.equals(grade(row.grade()))
                    && (higher.isEmpty() || row.date().isAfter(higher.get()))).toList();
            var annual = history.stream().filter(row -> row.date().getYear() == year).toList();
            return new LicenseProgress(license.getKey(), currentGrade, source, registeredGrade, observedGrade, lastDate,
                    conflict, higher.isPresent(), history.isEmpty() ? null : history.getFirst().date(), lastDate,
                    CompetitionStatistics.summarize(annual), annualSummaries(history, today.getYear()), promotion(currentGrade, promotionHistory),
                    championship(currentGrade, annual, year), VERSION, RULES_URL);
        }).toList();
    }

    static List<AnnualSummary> annualSummaries(List<Entry> history, int currentYear) {
        return java.util.stream.IntStream.rangeClosed(currentYear - 2, currentYear).mapToObj(year -> {
            var rows = history.stream().filter(row -> row.date().getYear() == year).toList();
            return new AnnualSummary(year, rows.stream().map(Entry::date).min(Comparator.naturalOrder()).orElse(null),
                    rows.stream().map(Entry::date).max(Comparator.naturalOrder()).orElse(null),
                    rows.stream().map(Entry::grade).map(RsceProgress::grade).filter(Objects::nonNull).distinct().sorted().toList(),
                    CompetitionStatistics.summarize(rows));
        }).toList();
    }

    static String grade(String value) {
        if (value == null) return null;
        String normalized = value.toUpperCase(Locale.ROOT).strip().replaceFirst("^(?:GRADO|GRAU|GRADE|G)\\s*", "");
        return switch (normalized) {
            case "1", "I" -> "G1";
            case "2", "II" -> "G2";
            case "3", "III" -> "G3";
            default -> null;
        };
    }

    private static List<ProgressRoute> promotion(String grade, List<Entry> history) {
        if ("G1".equals(grade)) {
            return List.of(route("G1_THREE_AGILITY", "G2", 0, true,
                            target(history, Discipline.AGILITY, "CLEAR_EXCELLENT", 3)),
                    route("G1_TWO_EACH", "G2", 0, true,
                            target(history, Discipline.AGILITY, "CLEAR_EXCELLENT", 2),
                            target(history, Discipline.JUMPING, "CLEAR_EXCELLENT", 2)));
        }
        if ("G2".equals(grade)) {
            return List.of(route("G2_TO_G3", "G3", 0, false,
                    target(history, Discipline.AGILITY, "PA", 3), target(history, Discipline.JUMPING, "PA", 3)));
        }
        return List.of();
    }

    private static List<ProgressRoute> championship(String grade, List<Entry> annual, int year) {
        // Do not apply the supplied 2026 standard retroactively to older championship periods.
        if (year < 2026 || !Set.of("G2", "G3").contains(Objects.toString(grade, ""))) return List.of();
        var g2 = annual.stream().filter(row -> "G2".equals(grade(row.grade()))).toList();
        var g3 = annual.stream().filter(row -> "G3".equals(grade(row.grade()))).toList();
        if ("G2".equals(grade)) return List.of(g2Route("G2_STANDARD", g2, year));
        var routes = new ArrayList<ProgressRoute>();
        routes.add(route("G3_STANDARD", "G3", year + 1, false,
                target(g3, Discipline.AGILITY, "PV", 4), target(g3, Discipline.JUMPING, "PV", 4)));
        // Article 62: a pair promoted during the period may use the G2 route, subject to the club's prior notice.
        if (!g2.isEmpty() && !g3.isEmpty() && g2.stream().map(Entry::date).max(Comparator.naturalOrder()).orElseThrow()
                .isBefore(g3.stream().map(Entry::date).min(Comparator.naturalOrder()).orElseThrow())) {
            routes.add(g2Route("G2_AFTER_PROMOTION", annual.stream()
                    .filter(row -> Set.of("G2", "G3").contains(Objects.toString(grade(row.grade()), ""))).toList(), year));
        }
        return List.copyOf(routes);
    }

    private static ProgressRoute g2Route(String code, List<Entry> rows, int year) {
        return route(code, "G2", year + 1, false,
                target(rows, Discipline.AGILITY, "CHAMPIONSHIP_POINT", 6), target(rows, Discipline.AGILITY, "PV", 4),
                target(rows, Discipline.JUMPING, "CHAMPIONSHIP_POINT", 6), target(rows, Discipline.JUMPING, "PV", 4));
    }

    private static ProgressRoute route(String code, String grade, int year, boolean judges, PointTarget... targets) {
        return new ProgressRoute(code, grade, year, List.of(targets), Arrays.stream(targets).allMatch(target -> target.remaining() == 0), judges);
    }

    private static PointTarget target(List<Entry> rows, Discipline discipline, String kind, int required) {
        int earned = (int) rows.stream().filter(row -> row.discipline() == discipline && counts(row, kind)).count();
        return new PointTarget(discipline, kind, earned, required, Math.max(0, required - earned));
    }

    private static boolean counts(Entry row, String kind) {
        return switch (kind) {
            case "PA" -> row.point() == Point.PA || row.point() == Point.PA_PV;
            case "PV" -> row.point() == Point.PV || row.point() == Point.PA_PV;
            case "CHAMPIONSHIP_POINT" -> row.point() == Point.P || row.point() == Point.PV || row.point() == Point.PA_PV;
            // An explicit promotion award remains valid when an older print omits numerical penalties.
            case "CLEAR_EXCELLENT" -> CompetitionStatistics.hasOfficialPoint(row)
                    || CompetitionStatistics.clear(row)
                    && Set.of("EXC", "EXC_0", "EXC.", "EXCELENTE", "EXCELLENT", "EXCEL·LENT")
                    .contains(Objects.toString(row.qualification(), "").strip().toUpperCase(Locale.ROOT));
            default -> false;
        };
    }
}
