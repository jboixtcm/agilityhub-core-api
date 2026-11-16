package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.clubs.census.application.CompetitionDogDirectory.CompetitionLicense;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import static org.assertj.core.api.Assertions.*;

class RsceProgressTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    static Result row(String id, String license, String grade, String day, Discipline discipline, Point point) {
        return new Result(Source.FLOW_AGILITY, CompetitionParsersTest.FLOW_URL, Instant.parse("2026-11-16T08:00:00Z"),
                new Entry(id, license, "Núvol", "Laia Exemple", "Example club", day.substring(0, 4), null, "M", 1, null,
                        "trial", id, LocalDate.parse(day), "Example trial", grade, discipline, 0, 0, BigDecimal.ZERO,
                        BigDecimal.valueOf(35), BigDecimal.valueOf(5), "EXC", point));
    }
    static LicenseProgress progress(String grade, List<Result> rows) {
        return RsceProgress.calculate(rows, List.of(new CompetitionLicense("0123", grade)), 2026, TODAY).getFirst();
    }
    @Test void T_STATS_40_promotionKeepsPriorYearsButChampionshipExcludesG1AndCountsCombinedOnce() {
        var rows = List.of(row("old", "0123", "G2", "2025-12-31", Discipline.AGILITY, Point.PA),
                row("combined", "0123", "G2", "2026-01-01", Discipline.AGILITY, Point.PA_PV),
                row("p", "0123", "G2", "2026-11-15", Discipline.AGILITY, Point.P),
                row("pv", "0123", "G2", "2026-11-15", Discipline.JUMPING, Point.PV),
                row("beginner", "0123", "G1", "2026-01-01", Discipline.JUMPING, Point.PA),
                row("future", "0123", "G2", "2027-01-01", Discipline.AGILITY, Point.PA));
        var p = progress("G2", rows);
        assertThat(p.promotionRoutes().getFirst().targets()).containsExactly(
                new PointTarget(Discipline.AGILITY, "PA", 2, 3, 1), new PointTarget(Discipline.JUMPING, "PA", 0, 3, 3));
        assertThat(p.championshipRoutes().getFirst().championshipYear()).isEqualTo(2027);
        assertThat(p.championshipRoutes().getFirst().targets()).containsExactly(
                new PointTarget(Discipline.AGILITY, "CHAMPIONSHIP_POINT", 2, 6, 4), new PointTarget(Discipline.AGILITY, "PV", 1, 4, 3),
                new PointTarget(Discipline.JUMPING, "CHAMPIONSHIP_POINT", 1, 6, 5), new PointTarget(Discipline.JUMPING, "PV", 1, 4, 3));
        assertThat(p.summaries().getFirst().pa()).isEqualTo(1);
        assertThat(p.summaries().getFirst().pv()).isEqualTo(1);
        assertThat(p.summaries().getFirst().p()).isEqualTo(1);
        assertThat(p.historyFrom()).isEqualTo(LocalDate.of(2025, 12, 31));
    }
    @Test void T_STATS_41_g1ShowsBothAlternativesAndRequiresJudgesWithoutChampionship() {
        var rows = new ArrayList<Result>();
        for (int i = 0; i < 2; i++) for (var d : Discipline.values()) rows.add(row(i + d.name(), "0123", "G1", "2025-12-31", d, Point.PA));
        var p = progress("I", rows);
        assertThat(p.currentGrade()).isEqualTo("G1");
        assertThat(p.championshipRoutes()).isEmpty();
        assertThat(p.promotionRoutes()).hasSize(2).allMatch(ProgressRoute::requiresDifferentJudges);
        assertThat(p.promotionRoutes().getFirst().pointTargetsMet()).isFalse();
        assertThat(p.promotionRoutes().get(1).pointTargetsMet()).isTrue();
        rows.add(row("third", "0123", "G1", "2026-01-01", Discipline.AGILITY, Point.NONE));
        assertThat(progress("G1", rows).promotionRoutes().getFirst().pointTargetsMet()).isTrue();
        assertThat(progress("G1", rows).summaries().getFirst().pa()).isZero(); // No invented provider award.
    }
    @Test void T_STATS_42_g3OnlyUsesG3VelocityAndShowsNotifiedPromotionAlternative() {
        var rows = List.of(row("g2", "0123", "G2", "2026-01-01", Discipline.AGILITY, Point.PA_PV),
                row("g3a", "0123", "G3", "2026-02-01", Discipline.AGILITY, Point.PV),
                row("g3j", "0123", "G3", "2026-02-01", Discipline.JUMPING, Point.P),
                row("old", "0123", "G3", "2025-12-31", Discipline.AGILITY, Point.PV));
        var p = progress("III", rows);
        assertThat(p.promotionRoutes()).isEmpty();
        assertThat(p.championshipRoutes()).extracting(ProgressRoute::code).containsExactly("G3_STANDARD", "G2_AFTER_PROMOTION");
        assertThat(p.championshipRoutes().getFirst().targets()).containsExactly(
                new PointTarget(Discipline.AGILITY, "PV", 1, 4, 3), new PointTarget(Discipline.JUMPING, "PV", 0, 4, 4));
        assertThat(p.championshipRoutes().get(1).targets().getFirst().earned()).isEqualTo(2);
    }
    @Test void T_STATS_43_licensesCorrectionsAndFutureDatesNeverLeakIntoProgress() {
        var first = row("run", "0123", "G2", "2026-10-10", Discipline.AGILITY, Point.PA);
        var corrected = new Result(first.source(), first.sourceUrl(), first.importedAt().plusSeconds(1),
                row("run", "0123", "G2", "2026-10-10", Discipline.AGILITY, Point.NONE).result());
        var rows = List.of(first, corrected, row("run", "4567", "G3", "2026-10-10", Discipline.AGILITY, Point.PV),
                row("future", "0123", "G3", "2027-01-01", Discipline.AGILITY, Point.PV));
        var all = RsceProgress.calculate(rows, List.of(new CompetitionLicense("0123", "Grado II")), 2026, TODAY);
        assertThat(all).hasSize(2);
        assertThat(all.getFirst().promotionRoutes().getFirst().targets().getFirst().earned()).isZero();
        assertThat(all.getFirst().currentGrade()).isEqualTo("G2");
        assertThat(all.get(1).currentGrade()).isEqualTo("G3");
        assertThat(all.get(1).gradeSource()).isEqualTo("LAST_RESULT");
        assertThat(CompetitionStatistics.aggregate("dog", 2026, "2026-2027", rows).results()).hasSize(2);
    }
    @Test void T_STATS_44_unknownOrConflictingGradesNeverCauseAutomaticPromotion() {
        var empty = progress("pre-agility", List.of());
        assertThat(empty.currentGrade()).isNull(); assertThat(empty.gradeSource()).isEqualTo("UNKNOWN");
        assertThat(empty.promotionRoutes()).isEmpty(); assertThat(empty.championshipRoutes()).isEmpty();
        assertThat(empty.historyFrom()).isNull();
        var conflicting = List.of(row("one", "0123", "G1", "2026-01-01", Discipline.AGILITY, Point.PA),
                row("two", "0123", "G2", "2026-01-01", Discipline.JUMPING, Point.PA_PV));
        var p = progress("", conflicting);
        assertThat(p.gradeConflict()).isTrue(); assertThat(p.currentGrade()).isNull();
        assertThat(progress("G1", conflicting).gradeConflict()).isTrue();
        assertThat(progress("G1", List.of(conflicting.get(1))).gradeConflict()).isTrue();
        for (String value : List.of("G1", "1", "I", "Grau I", "Grade 1")) assertThat(RsceProgress.grade(value)).isEqualTo("G1");
        assertThat(RsceProgress.grade(null)).isNull();
    }
    @Test void T_STATS_45_demotionDoesNotReuseOldPromotionAwardsAndOlderChampionshipRulesAreNotGuessed() {
        var rows = List.of(row("old", "0123", "G2", "2024-10-10", Discipline.AGILITY, Point.PA),
                row("higher", "0123", "G3", "2025-01-01", Discipline.AGILITY, Point.PV),
                row("new", "0123", "G2", "2026-01-01", Discipline.AGILITY, Point.PA));
        var p = progress("G2", rows);
        assertThat(p.previousHigherGrade()).isTrue();
        assertThat(p.promotionRoutes().getFirst().targets().getFirst().earned()).isEqualTo(1);
        assertThat(RsceProgress.calculate(rows, List.of(new CompetitionLicense("0123", "G2")), 2025, TODAY).getFirst().championshipRoutes()).isEmpty();
    }
    @Test void T_STATS_46_surplusIsRetainedAndRemainingNeverBecomesNegative() {
        var rows = new ArrayList<Result>();
        for (int i = 0; i < 7; i++) for (var d : Discipline.values()) rows.add(row(i + d.name(), "0123", "G2", "2026-01-01", d, Point.PA_PV));
        var p = progress("G2", rows);
        assertThat(p.currentGrade()).isEqualTo("G2");
        assertThat(p.promotionRoutes()).allMatch(ProgressRoute::pointTargetsMet);
        assertThat(p.championshipRoutes()).allMatch(ProgressRoute::pointTargetsMet);
        assertThat(p.promotionRoutes().getFirst().targets().getFirst()).isEqualTo(new PointTarget(Discipline.AGILITY, "PA", 7, 3, 0));
    }
}
