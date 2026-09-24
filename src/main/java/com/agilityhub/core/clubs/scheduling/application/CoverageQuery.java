package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.dashboard.application.DogActivityQuery;
import com.agilityhub.core.clubs.dashboard.application.ports.BookingActivity;
import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionRepository;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CoverageQuery {
    private final PlanningContext context;
    private final TemplateQuery templates;
    private final WeekGenerationUseCase weeks;
    private final ClassSessionRepository sessions;
    private final DogActivityQuery dogs;
    private final com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort bookings;
    private final com.agilityhub.core.clubs.census.application.SchedulingRecipients recipients;
    private final ClubClock clock;
    public CoverageQuery(PlanningContext context, TemplateQuery templates, WeekGenerationUseCase weeks, ClassSessionRepository sessions,
            DogActivityQuery dogs, com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort bookings, com.agilityhub.core.clubs.census.application.SchedulingRecipients recipients, ClubClock clock) {
        this.context = context; this.templates = templates; this.weeks = weeks; this.sessions = sessions; this.dogs = dogs; this.bookings = bookings; this.recipients = recipients; this.clock = clock;
    }
    public PlanningViews.Coverage get(String templateId, String saturdayTemplateId, String weekId) {
        if ((templateId == null) == (weekId == null) || weekId != null && saturdayTemplateId != null) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var config = context.config();
        if (!config.get("levels.enabled", Boolean.class)) { throw new ApiException(ErrorCode.LEVELS_DISABLED); }
        var catalog = context.catalog(); var classes = new ArrayList<CoverageCalculator.ClassPlaces>();
        Map<String, Integer> booked = Map.of(); var zone = ZoneId.of(config.club().timeZone());
        if (weekId != null) {
            var week = weeks.require(weekId);
            sessions.forWeek(weekId).stream().filter(c -> c.state() != ClassState.CANCELLED).forEach(c -> classes.add(new CoverageCalculator.ClassPlaces(c.capacity(), c.levelIds())));
            var countsByLevel = new HashMap<String,Integer>();
            sessions.forWeek(weekId).stream().filter(c -> c.state()!=ClassState.CANCELLED).forEach(c -> bookings.bookedDogs(c.id()).forEach(id ->
                    recipients.dog(id).map(com.agilityhub.core.clubs.census.application.SchedulingRecipients.Dog::levelId).ifPresent(level -> countsByLevel.merge(level,1,Integer::sum))));
            booked = countsByLevel;
        } else {
            addTemplate(classes, templateId, TemplateKind.WEEKDAYS);
            if (saturdayTemplateId != null) { addTemplate(classes, saturdayTemplateId, TemplateKind.SATURDAY); }
        }
        int activeWeeks = config.get("coverage.activeDogWeeks", Integer.class);
        var counts = new HashMap<String, CoverageCalculator.Dogs>();
        for (var count : dogs.counts(clock.today(TenantContext.require()), zone, activeWeeks)) {
            counts.put(count.levelId(), new CoverageCalculator.Dogs(count.total(), count.withRecentBooking(), booked.getOrDefault(count.levelId(), 0)));
        }
        // R-06-06 (E29): the table lists only the active progression levels; Teràpia or Pendent never appear.
        var levels = catalog.activeProgression();
        for (var level : levels) {
            counts.putIfAbsent(level.id(), new CoverageCalculator.Dogs(0, 0, booked.getOrDefault(level.id(), 0)));
        }
        var thresholds = config.get("coverage.thresholds", Map.class);
        var limits = new CoverageCalculator.Thresholds(((Number) thresholds.get("ok")).intValue(), ((Number) thresholds.get("tight")).intValue(), ((Number) thresholds.get("short")).intValue());
        var names = new HashMap<String, String>(); levels.forEach(l -> names.put(l.id(), l.name().resolve(LocaleContext.current()).value()));
        var results = CoverageCalculator.calculate(levels.stream().map(SchedulingCatalog.Level::id).toList(), classes, counts, limits, weekId != null);
        return new PlanningViews.Coverage(weekId == null ? PlanningViews.CoverageScope.TEMPLATE : PlanningViews.CoverageScope.WEEK,
                new PlanningViews.CoverageThresholds(limits.ok(), limits.tight(), limits.shortThreshold()), activeWeeks,
                results.stream().map(r -> new PlanningViews.CoverageLevel(r.levelId(), names.get(r.levelId()), r.maxSeats(), r.propSeats(), r.dogsTotal(), r.dogsActive(),
                        r.maxRatioPct(), r.propRatioPct(), PlanningViews.CoverageStatus.valueOf(r.status().name()), r.booked())).toList());
    }
    private void addTemplate(List<CoverageCalculator.ClassPlaces> classes, String id, TemplateKind kind) {
        var template = templates.get(id);
        if (template.kind() != kind) { throw new ApiException(ErrorCode.TEMPLATE_KIND_MISMATCH); }
        template.classes().forEach(c -> classes.add(new CoverageCalculator.ClassPlaces(c.capacity(), c.levelIds())));
    }
}
