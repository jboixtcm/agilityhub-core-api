package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeekGenerationUseCase {
    private final WeekRepository weeks;
    private final ClassSessionRepository classes;
    private final TemplateQuery templates;
    private final PlanningContext context;
    private final ClubClock clubClock;
    private final Clock clock;
    private final AuditActorProvider actors;
    private final EventPublisher events;
    public WeekGenerationUseCase(WeekRepository weeks, ClassSessionRepository classes, TemplateQuery templates, PlanningContext context,
            ClubClock clubClock, Clock clock, AuditActorProvider actors, EventPublisher events) {
        this.weeks = weeks; this.classes = classes; this.templates = templates; this.context = context;
        this.clubClock = clubClock; this.clock = clock; this.actors = actors; this.events = events;
    }
    public record Created(PlanningViews.Week week, boolean created) { }
    @PreAuthorize("hasRole('ADMIN')")
    public Created create(LocalDate start) {
        WeekCalendarRules.requireMonday(start); var now = clock.instant(); var actor = actors.current().accountId();
        var proposed = new Week(UUID.randomUUID().toString(), TenantContext.require(), WeekCalendarRules.isoYear(start), WeekCalendarRules.isoWeek(start),
                start, start.plusDays(6), WeekState.PENDING, null, null, null, null, null, null, 0L, now, actor, now, actor);
        var saved = weeks.getOrCreate(proposed);
        return new Created(view(saved), saved.id().equals(proposed.id()));
    }
    public Week require(String id) { return weeks.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public PlanningViews.Week get(String id) { return view(require(id)); }
    public PlanningViews.GenerationCandidates candidates() {
        var current = WeekCalendarRules.monday(clubClock.today(TenantContext.require()));
        var existing = new HashMap<LocalDate, Week>(); weeks.findAll().forEach(w -> existing.put(w.startDate(), w));
        var proposed = existing.values().stream().filter(w -> w.generatedAt() != null).map(Week::startDate).max(Comparator.naturalOrder()).map(d -> d.plusWeeks(1)).orElse(current);
        if (proposed.isBefore(current)) { proposed = current; }
        var result = new ArrayList<PlanningViews.GenerationCandidate>(); boolean selected = false;
        for (int offset = 0; offset <= 12; offset++) {
            var start = current.plusWeeks(offset); var saved = existing.get(start);
            if (saved != null && (saved.generatedAt() != null || saved.state() == WeekState.VALIDATED)) { continue; }
            boolean proposal = !selected && !start.isBefore(proposed); selected |= proposal;
            result.add(new PlanningViews.GenerationCandidate(start, start.plusDays(6), WeekCalendarRules.isoYear(start), WeekCalendarRules.isoWeek(start),
                    saved == null ? null : saved.id(), saved == null ? WeekState.PENDING : saved.state(), proposal));
        }
        return new PlanningViews.GenerationCandidates(List.copyOf(result));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PlanningViews.GenerationResult generate(String id, String weekdayTemplateId, String saturdayTemplateId) {
        try { return generateInTransaction(id, weekdayTemplateId, saturdayTemplateId); }
        catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.mongodb.MongoException mongo && (mongo.getCode() == 112 || mongo.getCode() == 11000)) {
                    throw new ApiException(ErrorCode.STALE_VERSION);
                }
            }
            throw failure;
        }
    }
    private PlanningViews.GenerationResult generateInTransaction(String id, String weekdayTemplateId, String saturdayTemplateId) {
        var before = require(id);
        if (before.generatedAt() != null || before.state() == WeekState.VALIDATED) { throw new ApiException(ErrorCode.WEEK_ALREADY_GENERATED); }
        var today = clubClock.today(TenantContext.require());
        if (before.endDate().isBefore(today)) { throw new ApiException(ErrorCode.WEEK_IN_PAST); }
        var now = clock.instant(); var actor = actors.current();
        try {
        weeks.update(new Week(before.id(), before.clubId(), before.isoYear(), before.isoWeek(), before.startDate(), before.endDate(), WeekState.GENERATED,
                now, actor.accountId(), weekdayTemplateId, saturdayTemplateId, before.validatedAt(), before.validatedByAccountId(), before.version() + 1,
                before.createdAt(), before.createdByAccountId(), now, actor.accountId()), before.version());
        } catch (DataAccessException conflict) {
            for (Throwable cause = conflict; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.mongodb.MongoException mongo && mongo.getCode() == 112) { throw new ApiException(ErrorCode.WEEK_ALREADY_GENERATED); }
            }
            throw conflict;
        }
        context.lockReferences();
        var sources = new ArrayList<WeekTemplate>(); sources.add(template(weekdayTemplateId, TemplateKind.WEEKDAYS));
        if (saturdayTemplateId != null) { sources.add(template(saturdayTemplateId, TemplateKind.SATURDAY)); }
        var catalog = context.catalog(); var inconsistencies = sources.stream().flatMap(t -> templates.inconsistencies(t, catalog).stream()).toList();
        if (!inconsistencies.isEmpty()) { throw new ApiException(ErrorCode.TEMPLATE_INCONSISTENT, Map.of("inconsistencies", inconsistencies)); }
        var config = context.config(); var zone = ZoneId.of(config.club().timeZone());
        var holidays = new HashSet<String>(); config.get("club.holidays", List.class).forEach(h -> holidays.add(h instanceof Map<?, ?> holiday ? holiday.get("date").toString() : h.toString()));
        record Skip(LocalDate date, PlanningViews.SkipReason reason) { }
        var skipped = new LinkedHashMap<Skip, Integer>(); int count = 0;
        for (var source : sources) {
            var bands = new HashMap<String, WeekTemplate.TimeBand>(); source.timeBands().forEach(b -> bands.put(b.id(), b));
            for (var c : source.classes()) {
                var date = before.startDate().plusDays(c.dayOfWeek().getValue() - 1L);
                var reason = holidays.contains(date.toString()) ? PlanningViews.SkipReason.HOLIDAY : date.isBefore(today) ? PlanningViews.SkipReason.PAST : null;
                if (reason != null) { skipped.merge(new Skip(date, reason), 1, Integer::sum); continue; }
                var band = bands.get(c.bandId());
                var start = resolve(date, band.startTime(), zone); var end = resolve(date, band.endTime(), zone);
                classes.insert(new ClassSession(UUID.randomUUID().toString(), before.clubId(), id, date, band.startTime(), band.endTime(), start, end,
                        c.ringId(), c.levelIds(), c.instructorIds(), context.capacity(c.levelIds(), c.capacityMode(), c.capacity(), catalog, config), c.capacityMode(), c.description(),
                        ClassState.DRAFT, new ClassSession.Counters(0, 0), new ClassSession.Risk(false, List.of(), null, null), null,
                        new ClassSession.Origin(source.id(), c.id()), c.placementId(), null, 0L, now, actor.accountId(), now, actor.accountId())); count++;
            }
        }
        events.publish(new SchedulingEvent(SchedulingEvent.Kind.WeekGenerated, before.clubId(), before.id(), now,
                Map.of("weekId", id, "classCount", count, "templateId", weekdayTemplateId), actor.accountId(), actor.impersonatedMemberId(), DomainEvent.Origin.BACKOFFICE));
        return new PlanningViews.GenerationResult(id, count, skipped.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().date()))
                .map(e -> new PlanningViews.SkippedClasses(e.getKey().date(), e.getKey().reason(), e.getValue())).toList());
    }
    private WeekTemplate template(String id, TemplateKind kind) {
        var template = templates.require(id);
        if (template.kind() != kind) { throw new ApiException(ErrorCode.TEMPLATE_KIND_MISMATCH); }
        if (!template.active()) { throw new ApiException(ErrorCode.INVALID_STATE); }
        return template;
    }
    private Instant resolve(LocalDate date, String time, ZoneId zone) {
        var resolved = WeekCalendarRules.resolve(date, LocalTime.parse(time), zone);
        if (resolved.shifted()) { org.slf4j.LoggerFactory.getLogger(WeekGenerationUseCase.class).warn("Scheduling local time shifted date={} time={} zone={} resolved={}", date, time, zone, resolved.resolvedLocal()); }
        return resolved.instant();
    }
    private PlanningViews.Week view(Week w) {
        return new PlanningViews.Week(w.id(), w.isoYear(), w.isoWeek(), w.startDate(), w.endDate(), w.state(), w.generatedAt(), w.generatedByAccountId(),
                w.weekdayTemplateId(), w.saturdayTemplateId(), w.validatedAt(), w.validatedByAccountId(), w.version());
    }
}
