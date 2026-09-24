package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.bookings.application.BookableClassesCache;
import com.agilityhub.core.clubs.bookings.application.BookingContext;
import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.scheduling.application.WeekOpenings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-11 P1 `week-opening` (weekly at `bookings.weekOpensAt`, catch-up 24 h). No business write: W0/W1 are
 * functions of time (S08 R-08-01) and the training counter is per session week (S09 R-09-05). One transaction (the
 * single item): invalidate the club's configuration and grid caches ({@link ClubGridCaches}), warm the bookable-classes base cache (S08
 * {@link BookableClassesCache}), `targetWeek.openedAt = opensAt`, `WeekOpened` and, with FREE_TRAINING,
 * `TrainingCounterReset` (S09 invalidates its grid cache). N-33 goes out now when `messaging.notifyWeekOpening` and the
 * target week has an ACTIVE class; otherwise it is deferred to the `WeekValidated` consumer ({@link WeekOpeningNotifications}).
 */
@Component
public class WeekOpeningJob implements Job {
    private final WeekOpenings weeks; private final BookingContext bookings; private final BookableClassesCache bookable;
    private final SchedulingRecipients recipients; private final ClubConfigService configs; private final EventPublisher publisher; private final Clock clock;
    private final ObjectProvider<ClubGridCaches> grids;
    public WeekOpeningJob(WeekOpenings weeks, BookingContext bookings, BookableClassesCache bookable, SchedulingRecipients recipients,
            ClubConfigService configs, EventPublisher publisher, Clock clock, ObjectProvider<ClubGridCaches> grids) {
        this.weeks = weeks; this.bookings = bookings; this.bookable = bookable; this.recipients = recipients; this.configs = configs;
        this.publisher = publisher; this.clock = clock; this.grids = grids;
    }
    @Override public JobName name() { return JobName.WEEK_OPENING; }

    /** `opensAt` (the occurrence), `openedWeekKey` = its local date + 7 days, the ISO Monday of `openedWeekKey + 1 day`. */
    record Opening(Instant opensAt, LocalDate currentWeekKey, LocalDate openedWeekKey, LocalDate isoWeekStart) {
        static Opening of(Instant opensAt, java.time.ZoneId zone) {
            var current = opensAt.atZone(zone).toLocalDate(); var opened = current.plusDays(7);
            return new Opening(opensAt, current, opened, opened.plusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        }
    }
    private Opening opening(JobContext context) {
        context.parameter("bookings.weekOpensAt", Object.class);
        // A manual run covers the last opening at or before now, exactly like the scheduled occurrence.
        return Opening.of(bookings.lastOpening(context.scheduledFor()), context.zone());
    }

    @Override public List<JobItem> plan(JobContext context) {
        var opening = opening(context);
        boolean notify = Boolean.TRUE.equals(context.parameter("messaging.notifyWeekOpening", Boolean.class));
        var target = weeks.find(opening.isoWeekStart()).orElse(null);
        if (target != null && target.openedAt() != null && !target.openedAt().isBefore(opening.opensAt())) {
            context.recorder().count("alreadyOpened", 1);
            return List.of();
        }
        int active = target == null ? 0 : target.activeClasses();
        int audience = notify && active > 0 ? audience() : 0;
        return List.of(new JobItem("Week", opening.openedWeekKey().toString(), "OPEN", detail(opening, active, audience)));
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        var opening = opening(context); var clubId = TenantContext.require();
        // R-15-11 step (1): configuration and grid caches, here and whatever FREE_TRAINING says (E5-T09); S09's
        // `TrainingCounterReset` consumer below is only a second, asynchronous invalidation.
        configs.invalidate(clubId);
        grids.orderedStream().forEach(grid -> grid.invalidateClub(clubId));
        bookable.warm();
        boolean notifyParameter = Boolean.TRUE.equals(context.parameter("messaging.notifyWeekOpening", Boolean.class));
        int active = weeks.find(opening.isoWeekStart()).map(WeekOpenings.Target::activeClasses).orElse(0);
        boolean notify = notifyParameter && active > 0;
        weeks.open(opening.isoWeekStart(), opening.opensAt(), notify);
        int audience = notify ? audience() : 0;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("openedWeekKey", opening.openedWeekKey().toString()); payload.put("isoWeekStart", opening.isoWeekStart().toString());
        payload.put("currentWeekKey", opening.currentWeekKey().toString()); payload.put("opensAt", opening.opensAt().toString()); payload.put("notified", notify);
        publisher.publish(event(SchedulerEvent.Kind.WeekOpened, clubId, opening.openedWeekKey().toString(), payload));
        if (context.config().modules().contains(Module.FREE_TRAINING)) {
            publisher.publish(event(SchedulerEvent.Kind.TrainingCounterReset, clubId, clubId, Map.of("weekStart", opening.opensAt().toString())));
        }
        return new JobEffect("OPEN", detail(opening, active, audience), Map.of("opened", 1L, "activeClasses", (long) active, "notified", (long) audience));
    }

    private int audience() { return (int) recipients.activeWithActiveDog().stream().filter(member -> member.accountId() != null).count(); }
    private SchedulerEvent event(SchedulerEvent.Kind kind, String clubId, String aggregateId, Map<String, Object> payload) {
        return new SchedulerEvent(kind, clubId, aggregateId, clock.instant(), payload, null, null, DomainEvent.Origin.SYSTEM);
    }
    private static Map<String, Object> detail(Opening opening, int active, int audience) {
        return Map.of("weekKey", opening.openedWeekKey().toString(), "activeClasses", active, "recipients", audience);
    }
}
