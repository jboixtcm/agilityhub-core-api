package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.census.application.ReminderLeads;
import com.agilityhub.core.clubs.common.application.ports.ReminderSource;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-14 P4 `reminders` (every minute, no module of its own; continuous, so no catch-up). `now` is the run's minute
 * (`scheduledFor`). Scope: ACTIVE class bookings ({@link ClassReminderSource}) and, with `FREE_TRAINING`, ACTIVE training
 * bookings ({@link ReminderSource} of S09) starting in `(now, now + maxLead]` without `reminderSentAt`, where `maxLead =
 * max(messaging.reminderOptionsMinutes)`, joined with the `reminderMinutesBefore` of the dog's owner ({@link ReminderLeads}).
 * Due when `lead != null ∧ startsAt − lead ≤ now < startsAt ∧ bookedAt ≤ startsAt − lead`: a booking made after its own
 * reminder moment gets none (N-04/N-06 already confirmed it). Instant arithmetic only, so a DST change moves nothing.
 *
 * <p>Each item is one booking, applied in the runner's own transaction: it is read again (state, mark, the owner's lead
 * now — a preference changed after the plan applies at once), `reminderSentAt` is set once and `ReminderDue{bookingId |
 * trainingBookingId, memberId, dogId, startsAt}` goes to the outbox in the same transaction. The S11 engine turns it into
 * N-13 (R-11-16 `stillRelevant`). Counters `{classReminders, trainingReminders}`; a dry run traces `WOULD_REMIND {bookingId |
 * trainingBookingId, memberId, startsAt, lead}`. Nothing to roll back. A training reminder planned while `FREE_TRAINING` was
 * on is not applied once it is off.</p>
 */
@Component
public class RemindersJob implements Job {
    static final String REMIND = "REMIND", CLASS_ENTITY = "Booking", TRAINING_ENTITY = "TrainingBooking";
    static final String CLASS_COUNTER = "classReminders", TRAINING_COUNTER = "trainingReminders";
    private final Map<ReminderSource.Kind, ReminderSource> sources = new EnumMap<>(ReminderSource.Kind.class);
    private final ReminderLeads leads; private final EventPublisher publisher; private final Clock clock;

    public RemindersJob(List<ReminderSource> sources, ReminderLeads leads, EventPublisher publisher, Clock clock) {
        sources.forEach(source -> this.sources.put(source.kind(), source));
        this.leads = leads; this.publisher = publisher; this.clock = clock;
    }
    @Override public JobName name() { return JobName.REMINDERS; }

    @Override public List<JobItem> plan(JobContext context) {
        var now = context.scheduledFor();
        var maxLead = maxLead(context);
        if (maxLead.isEmpty()) { return List.of(); }
        var items = new ArrayList<JobItem>();
        for (var source : sources.values()) {
            if (!on(context, source.kind())) { continue; }
            var candidates = source.scope(now, now.plus(Duration.ofMinutes(maxLead.get())));
            var byMember = leads.of(candidates.stream().map(ReminderSource.Candidate::memberId).filter(Objects::nonNull).distinct().toList());
            for (var candidate : candidates) {
                Integer lead = byMember.get(candidate.memberId());
                if (due(candidate.startsAt(), candidate.bookedAt(), lead, now)) { items.add(item(source.kind(), candidate, lead)); }
            }
        }
        return items;
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        var kind = CLASS_ENTITY.equals(item.entityType()) ? ReminderSource.Kind.CLASS : ReminderSource.Kind.TRAINING;
        var source = sources.get(kind);
        var now = context.scheduledFor();
        if (source == null || !on(context, kind)) { return notInScope(); }
        var candidate = source.current(item.entityId()).orElse(null);
        if (candidate == null) { return notInScope(); }
        Integer lead = candidate.memberId() == null ? null : leads.of(List.of(candidate.memberId())).get(candidate.memberId());
        if (!due(candidate.startsAt(), candidate.bookedAt(), lead, now) || !source.markSent(candidate.id(), candidate.startsAt(), clock.instant())) { return notInScope(); }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(kind == ReminderSource.Kind.CLASS ? "bookingId" : "trainingBookingId", candidate.id());
        payload.put("memberId", candidate.memberId()); payload.put("dogId", candidate.dogId()); payload.put("startsAt", candidate.startsAt().toString());
        publisher.publish(new SchedulerEvent(SchedulerEvent.Kind.ReminderDue, TenantContext.require(), candidate.id(), clock.instant(), payload, null, null,
                DomainEvent.Origin.SYSTEM));
        return new JobEffect(REMIND, item(kind, candidate, lead).detail(), Map.of(kind == ReminderSource.Kind.CLASS ? CLASS_COUNTER : TRAINING_COUNTER, 1L));
    }

    /** R-15-14: `lead != null ∧ startsAt − lead ≤ now < startsAt ∧ bookedAt ≤ startsAt − lead`, in instants. */
    static boolean due(Instant startsAt, Instant bookedAt, Integer lead, Instant now) {
        if (lead == null || startsAt == null) { return false; }
        var moment = startsAt.minus(Duration.ofMinutes(lead));
        return !moment.isAfter(now) && now.isBefore(startsAt) && (bookedAt == null || !bookedAt.isAfter(moment));
    }

    /** `max(messaging.reminderOptionsMinutes)`; empty when the club offers no option (nobody can have a lead). */
    private static Optional<Integer> maxLead(JobContext context) {
        var options = context.parameter("messaging.reminderOptionsMinutes", List.class);
        if (options == null) { return Optional.empty(); }
        return ((List<?>) options).stream().filter(Number.class::isInstance).map(value -> ((Number) value).intValue()).max(Integer::compare);
    }
    private static boolean on(JobContext context, ReminderSource.Kind kind) {
        return kind == ReminderSource.Kind.CLASS || context.config().modules().contains(Module.FREE_TRAINING);
    }
    private static JobItem item(ReminderSource.Kind kind, ReminderSource.Candidate candidate, Integer lead) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put(kind == ReminderSource.Kind.CLASS ? "bookingId" : "trainingBookingId", candidate.id());
        detail.put("memberId", candidate.memberId()); detail.put("startsAt", candidate.startsAt().toString()); detail.put("lead", lead);
        return new JobItem(kind == ReminderSource.Kind.CLASS ? CLASS_ENTITY : TRAINING_ENTITY, candidate.id(), REMIND, detail);
    }
    private static JobEffect notInScope() { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
}
