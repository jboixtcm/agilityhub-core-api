package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.clubs.scheduling.domain.InconsistencyDetector.*;

class PlanningRulesTest {
    static SchedulingCatalog catalog() {
        var levels = new ArrayList<SchedulingCatalog.Level>(); int order = 0;
        for (String name : List.of("Cadells", "A", "B", "C", "D", "E", "F", "G")) {
            levels.add(new SchedulingCatalog.Level(name, new LocalizedText(Map.of("ca", name, "es", name, "en", name), "ca"), order++, name.equals("C") ? 4 : 5, true));
        }
        return new SchedulingCatalog(levels, List.of(new SchedulingCatalog.Resource("r", "Central", true), new SchedulingCatalog.Resource("inactive", "Closed", false)),
                List.of(new SchedulingCatalog.Resource("i", "Example", true), new SchedulingCatalog.Resource("j", "Second", true), new SchedulingCatalog.Resource("inactive", "Former", false)));
    }
    private static void error(Runnable call, ErrorCode code) { assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code)); }
    @Test void T_06_01_bandsRespectGranularityOverlapEveryOpeningDayAndDeletion() {
        var hours = new EnumMap<DayOfWeek, WeekTemplateRules.Opening>(DayOfWeek.class);
        Arrays.stream(DayOfWeek.values()).forEach(d -> hours.put(d, new WeekTemplateRules.Opening(LocalTime.of(7, 0), LocalTime.of(22, 0))));
        var before = new WeekTemplateRules.Band("a", LocalTime.of(17, 40), LocalTime.of(18, 40));
        for (var row : List.of(new Object[]{"18:00", "19:00", ErrorCode.BAND_OVERLAP}, new Object[]{"17:45", "18:40", ErrorCode.INVALID_SLOT_GRANULARITY},
                new Object[]{"06:30", "07:30", ErrorCode.OUTSIDE_OPENING_HOURS}, new Object[]{"21:30", "22:30", ErrorCode.OUTSIDE_OPENING_HOURS},
                new Object[]{"18:40", "18:40", ErrorCode.INVALID_TIME_RANGE}, new Object[]{"19:00", "18:00", ErrorCode.INVALID_TIME_RANGE},
                new Object[]{"19:00", "19:55", ErrorCode.INVALID_SLOT_GRANULARITY})) {
            error(() -> WeekTemplateRules.band(new WeekTemplateRules.Band("b", LocalTime.parse((String) row[0]), LocalTime.parse((String) row[1])), List.of(before), TemplateKind.WEEKDAYS, 10, hours), (ErrorCode) row[2]);
        }
        WeekTemplateRules.band(before, List.of(before), TemplateKind.WEEKDAYS, 10, hours);
        WeekTemplateRules.band(new WeekTemplateRules.Band("b", LocalTime.of(18, 40), LocalTime.of(19, 40)), List.of(before), TemplateKind.SATURDAY, 10, hours);
        hours.remove(DayOfWeek.FRIDAY); error(() -> WeekTemplateRules.band(before, List.of(), TemplateKind.WEEKDAYS, 10, hours), ErrorCode.OUTSIDE_OPENING_HOURS);
        WeekTemplateRules.removable(false); error(() -> WeekTemplateRules.removable(true), ErrorCode.BAND_NOT_EMPTY);
        error(() -> WeekTemplateRules.time("25:00"), ErrorCode.INVALID_TIME_RANGE); error(() -> WeekTemplateRules.time(null), ErrorCode.INVALID_TIME_RANGE);
        assertThat(WeekTemplateRules.time("08:30")).isEqualTo(LocalTime.of(8, 30));
    }
    @Test void T_06_02_T_06_34_descriptionsUseContiguousActiveTailAndReaderLocale() throws Exception {
        var resolver = new DescriptionResolver(new IcuMessageSource()::format); var catalog = catalog(); var ca = Locale.forLanguageTag("ca");
        for (var entry : Map.of(List.of("B", "C"), "B+C", List.of("C", "D", "E"), "C+D+E", List.of("G", "E", "F", "D"), "D i sup.",
                List.of("A", "C"), "A+C", List.of("Cadells"), "Cadells", List.of("G"), "G", List.of("unknown"), "").entrySet()) {
            assertThat(resolver.resolve(null, entry.getKey(), catalog, ca)).isEqualTo(entry.getValue());
        }
        for (var entry : Map.of("ca", "D i sup.", "es", "D y sup.", "en", "D and up").entrySet()) {
            var locale = Locale.forLanguageTag(entry.getKey());
            assertThat(resolver.resolve(" ", List.of("D", "E", "F", "G"), catalog, locale)).isEqualTo(entry.getValue());
            assertThat(resolver.resolve("Obed. urbana", List.of("D"), catalog, locale)).isEqualTo("Obed. urbana");
        }
        var inactive = new ArrayList<>(catalog.levels()); var g = inactive.removeLast(); inactive.add(new SchedulingCatalog.Level(g.id(), g.name(), g.order(), g.capacity(), false));
        assertThat(resolver.resolve(null, List.of("F", "G"), new SchedulingCatalog(inactive, catalog.rings(), catalog.instructors()), ca)).isEqualTo("F+G");
    }
    @Test void T_06_03_capacityReusesS05MinimumAndClubDefault() {
        assertThat(PlanningCatalogAccess.capacity(List.of(5, 4), true, 7)).isEqualTo(4);
        assertThat(PlanningCatalogAccess.capacity(List.of(), false, 7)).isEqualTo(7);
        assertThat(PlanningCatalogAccess.capacity(List.of(4), false, 7)).isEqualTo(7);
    }
    static ScheduledItem item(String id, Kind kind, String start, String end, String ring, List<String> instructors, List<String> levels) {
        return new ScheduledItem(id, kind, DayOfWeek.WEDNESDAY, null, LocalTime.parse(start), LocalTime.parse(end), "band", ring, instructors, levels);
    }
    @Test void T_06_04_T_06_19_conflictsAreStablePerResourceAndRespectHalfOpenIntervalsAndModules() throws Exception {
        var detector = new InconsistencyDetector(new IcuMessageSource()::format); var ca = Locale.forLanguageTag("ca");
        var a = item("a", Kind.CLASS, "18:50", "19:50", "r", List.of("i", "j"), List.of("B"));
        var b = item("b", Kind.CLASS, "19:00", "20:00", "r", List.of("i", "j"), List.of("B"));
        var conflicts = detector.detect(List.of(a, b), catalog(), false, ca);
        assertThat(conflicts).extracting(Inconsistency::type).containsExactlyInAnyOrder(InconsistencyType.RING_DOUBLE_BOOKED, InconsistencyType.INSTRUCTOR_DOUBLE_BOOKED, InconsistencyType.INSTRUCTOR_DOUBLE_BOOKED);
        assertThat(detector.detect(List.of(b, a), catalog(), false, ca)).isEqualTo(conflicts);
        assertThat(detector.detect(List.of(b, a), catalog(), false, Locale.ENGLISH)).extracting(Inconsistency::id).containsExactlyElementsOf(conflicts.stream().map(Inconsistency::id).toList());
        assertThat(detector.detect(List.of(a, item("b", Kind.CLASS, "19:50", "20:50", "r", List.of("i"), List.of())), catalog(), false, ca)).isEmpty();
        assertThat(detector.detect(List.of(item("a", Kind.CLASS, "18:00", "19:00", null, List.of("i"), List.of()), item("b", Kind.CLASS, "18:00", "19:00", null, List.of("j"), List.of())), catalog(), false, ca)).isEmpty();
        var block = item("0", Kind.BLOCK, "18:00", "20:00", "r", List.of(), List.of());
        assertThat(detector.detect(List.of(a, block), catalog(), false, ca)).extracting(Inconsistency::type).containsExactly(InconsistencyType.RING_BLOCKED);
        var training = item("t", Kind.TRAINING, "18:00", "20:00", "r", List.of(), List.of());
        assertThat(detector.detect(List.of(a, training), catalog(), false, ca)).isEmpty();
        assertThat(detector.detect(List.of(a, training), catalog(), true, ca)).extracting(Inconsistency::type).containsExactly(InconsistencyType.RING_TRAINING_CONFLICT);
        assertThat(detector.detect(List.of(block, training), catalog(), true, ca)).isEmpty();
        var dead = item("dead", Kind.CLASS, "20:00", "21:00", "inactive", List.of("inactive"), List.of("missing"));
        assertThat(detector.detect(List.of(dead), catalog(), false, ca)).extracting(Inconsistency::type).containsExactlyInAnyOrder(InconsistencyType.LEVEL_INACTIVE, InconsistencyType.RING_INACTIVE, InconsistencyType.INSTRUCTOR_INACTIVE);
        var tomorrow = new ScheduledItem("next", Kind.CLASS, DayOfWeek.THURSDAY, null, a.start(), a.end(), "band", "r", List.of("i"), List.of());
        assertThat(detector.detect(List.of(a, tomorrow), catalog(), true, ca)).isEmpty();
    }
    @Test void T_06_05_coverageMatchesExamplesRoundedThresholdsAndZeroDenominators() {
        var limits = new CoverageCalculator.Thresholds(240, 190, 150);
        var classes = List.of(new CoverageCalculator.ClassPlaces(5, List.of("A")), new CoverageCalculator.ClassPlaces(5, List.of("A", "B")), new CoverageCalculator.ClassPlaces(4, List.of("B", "C")));
        var result = CoverageCalculator.calculate(List.of("A", "B", "C", "D"), classes, Map.of("A", new CoverageCalculator.Dogs(4, 3, 2), "B", new CoverageCalculator.Dogs(5, 3, 1), "C", new CoverageCalculator.Dogs(2, 1, 0)), limits, true);
        assertThat(result).extracting(CoverageCalculator.Coverage::maxRatioPct).containsExactly(250, 180, 200, null);
        assertThat(result).extracting(CoverageCalculator.Coverage::propRatioPct).containsExactly(250, 150, 200, null);
        assertThat(result).extracting(CoverageCalculator.Coverage::status).containsExactly(CoverageCalculator.Status.OK, CoverageCalculator.Status.SHORT, CoverageCalculator.Status.TIGHT, CoverageCalculator.Status.NO_DOGS);
        assertThat(result.getFirst().booked()).isEqualTo(2);
        for (var row : Map.of(240, CoverageCalculator.Status.TIGHT, 190, CoverageCalculator.Status.TIGHT, 189, CoverageCalculator.Status.SHORT, 149, CoverageCalculator.Status.EXPAND).entrySet()) { assertThat(CoverageCalculator.status(row.getKey(), limits)).isEqualTo(row.getValue()); }
        assertThat(CoverageCalculator.calculate(List.of("A"), classes, Map.of("A", new CoverageCalculator.Dogs(4, 0, 0)), limits, false).getFirst().booked()).isNull();
    }
    @Test void T_06_09_templateClassValidationAndVariants() {
        var catalog = catalog();
        TemplateClassRules.validate(TemplateKind.WEEKDAYS, Set.of("band"), "band", DayOfWeek.MONDAY, List.of("i", "j"), null, List.of("B"), null, 2, true, catalog);
        TemplateClassRules.validate(TemplateKind.SATURDAY, Set.of("band"), "band", DayOfWeek.SATURDAY, List.of("i"), "r", List.of(), "Manual", 1, false, catalog);
        error(() -> validate("foreign", DayOfWeek.MONDAY, List.of("i"), null, List.of("B"), null, true), ErrorCode.VALIDATION_ERROR);
        error(() -> validate("band", DayOfWeek.SUNDAY, List.of("i"), null, List.of("B"), null, true), ErrorCode.VALIDATION_ERROR);
        for (var ids : List.of(List.<String>of(), List.of("i", "j"), List.of("i", "i"))) { error(() -> validate("band", DayOfWeek.MONDAY, ids, null, List.of("B"), null, true), ErrorCode.TOO_MANY_INSTRUCTORS); }
        error(() -> validate("band", DayOfWeek.MONDAY, List.of("i"), null, List.of(), null, true), ErrorCode.LEVEL_REQUIRED);
        for (String text : Arrays.asList(null, " ")) { error(() -> validate("band", DayOfWeek.MONDAY, List.of("i"), null, List.of(), text, false), ErrorCode.DESCRIPTION_REQUIRED); }
        error(() -> validate("band", DayOfWeek.MONDAY, List.of("inactive"), null, List.of("B"), null, true), ErrorCode.VALIDATION_ERROR);
        for (var levels : List.of(List.of("B", "B"), List.of("missing"))) { error(() -> validate("band", DayOfWeek.MONDAY, List.of("i"), null, levels, null, true), ErrorCode.VALIDATION_ERROR); }
        error(() -> validate("band", DayOfWeek.MONDAY, List.of("i"), "inactive", List.of("B"), null, true), ErrorCode.VALIDATION_ERROR);
    }
    private void validate(String band, DayOfWeek day, List<String> instructors, String ring, List<String> levels, String text, boolean enabled) {
        TemplateClassRules.validate(TemplateKind.WEEKDAYS, Set.of("band"), band, day, instructors, ring, levels, text, 1, enabled, catalog());
    }
    @Test void T_06_33_isoWeeksDstGapsAndAmbiguousTimesUseClubZone() {
        var madrid = ZoneId.of("Europe/Madrid"); var time = LocalTime.of(8, 30);
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026, 10, 24), time, madrid).instant()).isEqualTo(Instant.parse("2026-10-24T06:30:00Z"));
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026, 10, 26), time, madrid).instant()).isEqualTo(Instant.parse("2026-10-26T07:30:00Z"));
        var gap = WeekCalendarRules.resolve(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), madrid);
        assertThat(gap.shifted()).isTrue(); assertThat(gap.resolvedLocal().getHour()).isEqualTo(3);
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), madrid).instant()).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(WeekCalendarRules.reviewAt(LocalDate.of(2026, 10, 26), LocalTime.of(7, 30), madrid)).isEqualTo(Instant.parse("2026-10-26T06:30:00Z"));
        var date = LocalDate.of(2027, 1, 1); assertThat(WeekCalendarRules.monday(date)).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(WeekCalendarRules.isoYear(date)).isEqualTo(2026); assertThat(WeekCalendarRules.isoWeek(date)).isEqualTo(53);
        WeekCalendarRules.requireMonday(WeekCalendarRules.monday(date)); error(() -> WeekCalendarRules.requireMonday(date), ErrorCode.VALIDATION_ERROR);
        var argentina = ZoneId.of("America/Argentina/Buenos_Aires");
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026, 10, 26), time, argentina).instant().atZone(ZoneOffset.UTC).toLocalTime()).isEqualTo(LocalTime.of(11, 30));
    }
}
