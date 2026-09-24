package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.scheduling.domain.ClassCancellationReason;
import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.domain.WeekCalendarRules;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionRepository;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-12 P2 `risk-review` (daily at `classes.riskReviewTime`). Scope: ACTIVE classes of `[today, today +
 * classes.riskLookaheadDays]`, not exempt, whose counted dogs (ACTIVE + PAYMENT_PENDING bookings, recounted through
 * {@link ClassBookingsPort}, never `counters.booked`) are below `classes.minDogs`. Today's classes are cancelled with
 * S06's transaction when `classes.riskAutoCancelSameDay` (a class that has already started is only counted in
 * `skippedStarted`); the rest are warned once per booking and once for the admins (`risk` marks). Each item runs in the
 * runner's transaction, which S06's cancellation joins (R-15-10).
 */
@Component
public class RiskReviewJob implements Job {
    static final String CANCEL = "CANCEL", NOTIFY = "NOTIFY";
    private final ClassSessionRepository classes; private final ClassBookingsPort bookings; private final ClassCancellationUseCase cancellations;
    private final SchedulingEvents events; private final IcuMessageSource messages; private final Clock clock;
    public RiskReviewJob(ClassSessionRepository classes, ClassBookingsPort bookings, ClassCancellationUseCase cancellations, SchedulingEvents events,
            IcuMessageSource messages, Clock clock) {
        this.classes = classes; this.bookings = bookings; this.cancellations = cancellations; this.events = events; this.messages = messages; this.clock = clock;
    }
    @Override public JobName name() { return JobName.RISK_REVIEW; }

    private record Policy(int minDogs, int lookahead, boolean autoCancel, LocalTime reviewTime) { }
    private static Policy policy(JobContext context) {
        return new Policy(context.parameter("classes.minDogs", Integer.class), context.parameter("classes.riskLookaheadDays", Integer.class),
                context.parameter("classes.riskAutoCancelSameDay", Boolean.class), LocalTime.parse(context.parameter("classes.riskReviewTime", String.class)));
    }

    @Override public List<JobItem> plan(JobContext context) {
        var policy = policy(context);
        var today = context.localDate(); Instant now = clock.instant();
        var from = today.atStartOfDay(context.zone()).toInstant(); var to = today.plusDays(policy.lookahead() + 1L).atStartOfDay(context.zone()).toInstant();
        var items = new ArrayList<JobItem>();
        for (ClassSession c : classes.findActiveBetween(context.clubId(), from, to).stream().sorted(Comparator.comparing(ClassSession::startsAt).thenComparing(ClassSession::id)).toList()) {
            context.recorder().count("reviewed", 1);
            if (c.risk() != null && c.risk().exempt()) { context.recorder().count("exempt", 1); continue; }
            var live = bookings.activeBookings(c.id());
            if (live.size() >= policy.minDogs()) { continue; }
            if (c.date().equals(today) && policy.autoCancel()) {
                // R-15-05: a late (CATCH_UP) run never cancels a class that has begun.
                if (!c.startsAt().isAfter(now)) { context.recorder().count("skippedStarted", 1); continue; }
                items.add(new JobItem("ClassSession", c.id(), CANCEL, cancelDetail(c.id(), live)));
                continue;
            }
            var fresh = fresh(c, live); boolean admins = adminsPending(c);
            if (fresh.isEmpty() && !admins) { context.recorder().count("atRisk", 1); continue; }
            items.add(new JobItem("ClassSession", c.id(), NOTIFY, notifyDetail(c.id(), fresh, admins)));
        }
        return items;
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        var policy = policy(context);
        var c = classes.findById(item.entityId()).orElse(null);
        if (c == null || c.state() != ClassState.ACTIVE || c.risk() != null && c.risk().exempt()) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        // Recounted inside the transaction (R-15-12): never trust the denormalized counters.
        var live = bookings.activeBookings(c.id());
        if (live.size() >= policy.minDogs()) { return new JobEffect("NOT_AT_RISK", Map.of(), Map.of()); }
        return CANCEL.equals(item.action()) ? cancel(context, c, live, policy) : warn(c, live, policy, context);
    }

    private JobEffect cancel(JobContext context, ClassSession c, List<ClassBookingsPort.BookingRef> live, Policy policy) {
        if (!c.startsAt().isAfter(clock.instant())) { return new JobEffect("SKIPPED_STARTED", Map.of(), Map.of("skippedStarted", 1L)); }
        var waiting = bookings.liveWaitlist(c.id());
        var locale = Locale.forLanguageTag(context.config().club().defaultLocale());
        String adminText = messages.format("scheduling.autoCancel.text", Map.of("minDogs", policy.minDogs()), locale);
        cancellations.cancel(c.id(), ClassCancellationReason.RISK_REVIEW, adminText, null);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("classId", c.id()); payload.put("dogsCount", live.size());
        payload.put("affected", live.stream().map(b -> Map.of("bookingId", b.bookingId(), "memberId", b.memberId(), "dogId", b.dogId())).toList());
        payload.put("waitlistIds", waiting.stream().map(ClassBookingsPort.WaitlistRef::entryId).toList());
        payload.put("adminText", adminText);
        events.publish(SchedulerEvent.Kind.ClassAutoCancelled, c.id(), payload);
        var counters = new LinkedHashMap<String, Long>(); counters.put("cancelled", 1L);
        if (live.isEmpty()) { counters.put("cancelledSilent", 1L); }
        return new JobEffect(CANCEL, cancelDetail(c.id(), live), counters);
    }

    private JobEffect warn(ClassSession c, List<ClassBookingsPort.BookingRef> live, Policy policy, JobContext context) {
        var fresh = fresh(c, live); boolean admins = adminsPending(c);
        if (fresh.isEmpty() && !admins) { return new JobEffect("ALREADY_NOTIFIED", Map.of(), Map.of("atRisk", 1L)); }
        var now = clock.instant(); var risk = c.risk() == null ? new ClassSession.Risk(false, List.of(), null, null) : c.risk();
        var notified = new ArrayList<>(risk.notifiedBookingIds() == null ? List.<String>of() : risk.notifiedBookingIds()); notified.addAll(fresh);
        var edit = new SessionEdit(c);
        edit.risk = new ClassSession.Risk(risk.exempt(), List.copyOf(notified), admins ? now : risk.adminNotifiedAt(), risk.lowAlertSentAt());
        classes.update(edit.snapshot(now, events.actor()), c.version());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("classId", c.id()); payload.put("dogsCount", live.size());
        payload.put("reviewAt", WeekCalendarRules.resolve(c.date(), policy.reviewTime(), context.zone()).instant().toString());
        payload.put("newBookingIds", fresh); payload.put("notifyAdmins", admins);
        events.publish(SchedulerEvent.Kind.ClassAtRisk, c.id(), payload);
        return new JobEffect(NOTIFY, notifyDetail(c.id(), fresh, admins), Map.of("atRisk", 1L, "notifiedMembers", (long) fresh.size()));
    }

    private static List<String> fresh(ClassSession c, List<ClassBookingsPort.BookingRef> live) {
        var known = c.risk() == null || c.risk().notifiedBookingIds() == null ? Set.<String>of() : Set.copyOf(c.risk().notifiedBookingIds());
        return live.stream().map(ClassBookingsPort.BookingRef::bookingId).filter(id -> !known.contains(id)).toList();
    }
    private static boolean adminsPending(ClassSession c) { return c.risk() == null || c.risk().adminNotifiedAt() == null; }
    private static Map<String, Object> cancelDetail(String classId, List<ClassBookingsPort.BookingRef> live) {
        return Map.of("classId", classId, "dogsCount", live.size(), "affected", live.stream().map(ClassBookingsPort.BookingRef::bookingId).toList());
    }
    private static Map<String, Object> notifyDetail(String classId, List<String> fresh, boolean admins) {
        return Map.of("classId", classId, "newBookingIds", fresh, "notifyAdmins", admins);
    }
}
