package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** E4-T05 demo planning: templates, generated/validated weeks, loose classes, ring blocks and risk cancellations,
 * all through the S06 use cases. Every date is relative to the run's club-local week start. */
@Service
public class DemoPlanningSeeder implements DemoSeedStep {
    public record Spec(List<TemplateSpec> templates, List<WeekSpec> weeks, List<ClassSpec> looseClasses, List<BlockSpec> ringBlocks,
            List<SlotRef> riskCancellations) {
        public Spec {
            templates = List.copyOf(Objects.requireNonNull(templates)); weeks = List.copyOf(Objects.requireNonNull(weeks));
            looseClasses = looseClasses == null ? List.of() : List.copyOf(looseClasses); ringBlocks = ringBlocks == null ? List.of() : List.copyOf(ringBlocks);
            riskCancellations = riskCancellations == null ? List.of() : List.copyOf(riskCancellations);
        }
    }
    public record TemplateSpec(String name, TemplateKind kind, String copyFrom, List<String> bands, List<ClassSpec> classes) {
        public TemplateSpec { bands = bands == null ? List.of() : List.copyOf(bands); classes = classes == null ? List.of() : List.copyOf(classes); }
    }
    public record ClassSpec(Integer week, DayOfWeek day, String start, String end, String ring, List<String> levels, int instructor,
            Integer capacity, String description) {
        public ClassSpec { levels = List.copyOf(Objects.requireNonNull(levels)); }
    }
    public record WeekSpec(int offset, String weekdays, String saturday, boolean validate) { }
    public record BlockSpec(int week, DayOfWeek day, String from, String to, String ring, RingBlockKind kind, RingBlockReason reason, String note) { }
    public record SlotRef(int week, DayOfWeek day, String start, String ring) {
        public LocalDate date(LocalDate weekStart) { return DemoPlanningSeeder.date(weekStart, week, day); }
    }
    private final TemplateService templates; private final TemplateQuery query; private final WeekGenerationUseCase generation;
    private final WeekValidationUseCase validation; private final ClassSessionService sessions; private final RingBlockService blocks;
    private final ClassCancellationUseCase cancellations; private final PlanningCatalogAccess catalogs; private final PlanningContext context;
    private final IcuMessageSource messages; private final ObjectMapper mapper; private final com.agilityhub.core.clubs.census.application.SchedulingRecipients recipients;
    public DemoPlanningSeeder(TemplateService templates, TemplateQuery query, WeekGenerationUseCase generation, WeekValidationUseCase validation,
            ClassSessionService sessions, RingBlockService blocks, ClassCancellationUseCase cancellations, PlanningCatalogAccess catalogs,
            PlanningContext context, IcuMessageSource messages, ObjectMapper mapper, com.agilityhub.core.clubs.census.application.SchedulingRecipients recipients) {
        this.templates = templates; this.query = query; this.generation = generation; this.validation = validation; this.sessions = sessions;
        this.blocks = blocks; this.cancellations = cancellations; this.catalogs = catalogs; this.context = context; this.messages = messages; this.mapper = mapper;
        this.recipients = recipients;
    }
    @Override public int order() { return 10; }
    @Override public boolean reanchors() { return true; }
    public static LocalDate date(LocalDate weekStart, int week, DayOfWeek day) { return weekStart.plusWeeks(week).plusDays(day.getValue() - 1L); }
    @Override public Map<String, Integer> apply(Input input) {
        var spec = mapper.convertValue(Objects.requireNonNull(input.specification().get("planning"), "planning"), Spec.class);
        var rings = catalogs.ringIdsByShortName(); var levels = catalogs.levelIdsByCode(); var instructors = catalogs.activeInstructorIds();
        var counts = new LinkedHashMap<String, Integer>(); var templateIds = new LinkedHashMap<String, String>();
        if (input.reanchor()) {
            // E5-T09 `--reanchor`: the templates of the first run are reused by name (D3 is not re-created).
            query.list(null, null).items().forEach(t -> templateIds.putIfAbsent(t.name(), t.id()));
        } else {
            for (var t : spec.templates()) {
                String id = templates.create(t.name(), t.kind(), t.copyFrom() == null ? null : require(templateIds, t.copyFrom())).id();
                for (String band : t.bands()) { var times = band.split("-", 2); templates.addBand(id, times[0], times[1]); }
                var bands = new HashMap<String, String>(); query.require(id).timeBands().forEach(b -> bands.put(b.startTime(), b.id()));
                for (var c : t.classes()) {
                    templates.addClass(id, require(bands, c.start()), c.day(), List.of(instructor(instructors, c.instructor())), require(rings, c.ring()),
                            c.levels().stream().map(code -> require(levels, code)).toList(), c.capacity(), c.description());
                    counts.merge("templateClasses", 1, Integer::sum);
                }
                templateIds.put(t.name(), id); counts.merge("templates", 1, Integer::sum);
            }
        }
        // The week offsets generated by this run (`input.generatedWeeks()`): a re-anchored run keeps every week that already
        // exists (R-06: a generated or validated week is never generated again) and dates the loose classes, blocks,
        // cancellations and (S08 step) registrants only into the weeks it generated itself.
        for (var w : spec.weeks()) {
            var week = generation.create(input.weekStart().plusWeeks(w.offset())).week();
            if (input.reanchor() && (week.generatedAt() != null || week.state() == WeekState.VALIDATED)) {
                counts.merge("keptWeeks", 1, Integer::sum);
                // E5-T14 (review E5-T09 #4): a weekly re-anchor turns the old draft W+1 into the current week; when its row
                // says `validate`, it is validated so the current week has bookable classes. A past week is left alone.
                if (w.validate() && week.state() == WeekState.GENERATED && !week.endDate().isBefore(input.today()) && sessions.hasDrafts(week.id())) {
                    validation.validate(week.id()); counts.merge("validatedKeptWeeks", 1, Integer::sum);
                }
                continue;
            }
            var result = generation.generate(week.id(), require(templateIds, w.weekdays()), w.saturday() == null ? null : require(templateIds, w.saturday()));
            counts.merge("weeks", 1, Integer::sum); counts.merge("generatedClasses", result.classCount(), Integer::sum); input.generatedWeeks().add(w.offset());
            // The current week (E5-T06) keeps only its remaining days (R-06 PAST): on a Sunday nothing is left to validate.
            if (w.validate() && result.classCount() > 0) { validation.validate(week.id()); counts.merge("validatedWeeks", 1, Integer::sum); }
        }
        java.util.function.IntPredicate dated = input::dated;
        for (var c : spec.looseClasses()) {
            if (!dated.test(c.week())) { continue; }
            sessions.create(date(input.weekStart(), c.week(), c.day()), c.start(), c.end(), require(rings, c.ring()),
                    c.levels().stream().map(code -> require(levels, code)).toList(), List.of(instructor(instructors, c.instructor())), c.capacity(), c.description(), false);
            counts.merge("looseClasses", 1, Integer::sum);
        }
        var zone = ZoneId.of(context.config().club().timeZone());
        for (var b : spec.ringBlocks()) {
            if (!dated.test(b.week())) { continue; }
            var day = date(input.weekStart(), b.week(), b.day());
            blocks.create(require(rings, b.ring()), day.atTime(LocalTime.parse(b.from())).atZone(zone).toInstant(),
                    day.atTime(LocalTime.parse(b.to())).atZone(zone).toInstant(), b.kind(), b.reason(), b.note(), false);
            counts.merge("ringBlocks", 1, Integer::sum);
        }
        // The E5 scenario belongs to the seed login accounts of the first run: a re-anchored run never repeats it.
        if (!input.reanchor()) { counts.putAll(scenarioBlocks(input, rings, instructors, zone)); }
        var text = messages.format("scheduling.autoCancel.text", Map.of("minDogs", context.config().get("classes.minDogs", Integer.class)), Locale.forLanguageTag(context.config().club().defaultLocale()));
        for (var r : spec.riskCancellations()) {
            if (!dated.test(r.week())) { continue; }
            var slot = sessions.slot(r.date(input.weekStart()), r.start(), require(rings, r.ring())).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            cancellations.cancel(slot.id(), ClassCancellationReason.RISK_REVIEW, text, input.adminAccountId());
            counts.merge("riskCancellations", 1, Integer::sum);
        }
        return counts;
    }
    /** E5-T06 `scenario.ringBlocks`: reservations an instructor makes on a ring (screen 24, the grids), as that instructor. */
    public record ScenarioBlock(int instructor, DayOfWeek day, String from, String to, String ring, RingBlockKind kind, RingBlockReason reason, String note) { }
    private Map<String, Integer> scenarioBlocks(Input input, Map<String, String> rings, List<String> instructors, ZoneId zone) {
        List<ScenarioBlock> specs = mapper.convertValue(input.scenario().getOrDefault("ringBlocks", List.of()),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        for (var b : specs) {
            String member = catalogs.instructorMembers(List.of(instructor(instructors, b.instructor()))).getFirst();
            String account = recipients.member(member).map(com.agilityhub.core.clubs.census.application.SchedulingRecipients.Member::accountId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("instructor", b.instructor())));
            var day = date(input.weekStart(), 0, b.day());
            DemoSeedActor.as(account, "INSTRUCTOR", () -> blocks.create(require(rings, b.ring()), day.atTime(LocalTime.parse(b.from())).atZone(zone).toInstant(),
                    day.atTime(LocalTime.parse(b.to())).atZone(zone).toInstant(), b.kind(), b.reason(), b.note(), false));
        }
        return Map.of("scenarioRingBlocks", specs.size());
    }
    private static String instructor(List<String> instructors, int index) {
        if (index < 0 || index >= instructors.size()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("instructor", index)); }
        return instructors.get(index);
    }
    public static String require(Map<String, String> references, String key) {
        var value = references.get(key); if (value == null) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("reference", key)); } return value;
    }
}
