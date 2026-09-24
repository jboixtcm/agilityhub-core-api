package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-16 P6 `waitlist-fifo` (every minute, module WAITLIST and `waitlist.mode = FIFO`): a NOTIFIED entry whose
 * `confirmBy` has passed → EXPIRED + `WaitlistExpired{entryId, classId}`, one transaction per entry (the runner's, with
 * the class's seat lock). The `waitlist.WaitlistExpired` consumer ({@link WaitlistService#offerNext}) offers the seat to
 * the next entry. In ALL_AT_ONCE the framework records `SKIPPED{MODULE_OFF}` at most once per hour.
 */
@Component
public class WaitlistFifoJob implements Job {
    private final WaitlistEntryRepository waitlist; private final SeatLockRepository locks; private final WaitlistTransitions transitions;
    private final BookingCounters counters; private final EventPublisher publisher; private final Clock clock;
    public WaitlistFifoJob(WaitlistEntryRepository waitlist, SeatLockRepository locks, WaitlistTransitions transitions, BookingCounters counters,
            EventPublisher publisher, Clock clock) {
        this.waitlist = waitlist; this.locks = locks; this.transitions = transitions; this.counters = counters; this.publisher = publisher; this.clock = clock;
    }
    @Override public JobName name() { return JobName.WAITLIST_FIFO; }
    @Override public boolean activeFor(ClubConfig config) { return WaitlistMode.FIFO.name().equals(config.get("waitlist.mode", String.class)); }

    @Override public List<JobItem> plan(JobContext context) {
        context.parameter("waitlist.mode", String.class);
        return waitlist.notifiedDue(context.scheduledFor()).stream()
                .map(e -> new JobItem("WaitlistEntry", e.id(), "EXPIRE", Map.of("entryId", e.id(), "position", e.position()))).toList();
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        var initial = waitlist.findById(item.entityId()).orElse(null);
        if (initial == null) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        locks.lock(initial.classSessionId());
        WaitlistEntry e = waitlist.findById(item.entityId()).orElseThrow();
        // Idempotent by state: a claimed, cancelled or already expired offer is left alone.
        if (e.state() != WaitlistState.NOTIFIED || e.confirmBy() == null || e.confirmBy().isAfter(context.scheduledFor())) {
            return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of());
        }
        transitions.expire(e, clock.instant());
        counters.recount(e.classSessionId(), false, BookingActor.system());
        publisher.publish(new SchedulerEvent(SchedulerEvent.Kind.WaitlistExpired, TenantContext.require(), e.id(), clock.instant(),
                Map.of("entryId", e.id(), "classId", e.classSessionId()), null, null, DomainEvent.Origin.SYSTEM));
        return new JobEffect("EXPIRE", Map.of("entryId", e.id(), "position", e.position()), Map.of("expired", 1L));
    }
}
