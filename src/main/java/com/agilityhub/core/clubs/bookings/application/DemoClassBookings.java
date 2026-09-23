package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.annotation.*;

/**
 * Local/test stand-in for the S08 bookings of a class (E4-T05), backed by {@code demo_class_bookings}.
 * {@link #book} follows the S08 writer contract: it runs inside the caller's transaction and hands the new
 * counts to {@link ClassSessionService#bookingCounters}, which rechecks ACTIVE under the class version.
 * E5 registers the real {@link ClassBookingsPort}; this bean then backs off ({@code @ConditionalOnMissingBean}).
 */
public class DemoClassBookings implements ClassBookingsPort {
    private final DemoClassBookingRepository bookings; private final ClassSessionService sessions; private final Clock clock;
    public DemoClassBookings(DemoClassBookingRepository bookings, ClassSessionService sessions, Clock clock) {
        this.bookings = bookings; this.sessions = sessions; this.clock = clock;
    }
    @Override public List<BookingRef> activeBookings(String classId) {
        return bookings.forClass(classId, DemoClassBooking.State.ACTIVE).stream().map(b -> new BookingRef(b.id(), b.memberId(), b.dogId(), b.paidWithPack())).toList();
    }
    @Override public List<WaitlistRef> liveWaitlist(String classId) {
        return bookings.forClass(classId, DemoClassBooking.State.WAITLISTED).stream().map(DemoClassBookings::waitlist).toList();
    }
    @Override public List<WaitlistRef> waitlistEntries(List<String> entryIds) {
        if (entryIds.isEmpty()) { return List.of(); }
        var order = new HashMap<String, Integer>(); for (int i = 0; i < entryIds.size(); i++) { order.putIfAbsent(entryIds.get(i), i); }
        return bookings.byIds(entryIds).stream().filter(b -> b.position() != null).sorted(Comparator.comparing(b -> order.get(b.id())))
                .map(DemoClassBookings::waitlist).toList();
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public CancellationEffects cancelAllByClub(String classId, String reason, String actorAccountId) {
        var now = clock.instant(); var active = bookings.forClass(classId, DemoClassBooking.State.ACTIVE);
        var waiting = bookings.forClass(classId, DemoClassBooking.State.WAITLISTED);
        for (var b : active) { bookings.update(b.cancelled(reason, now), b.version()); }
        for (var b : waiting) { bookings.update(b.cancelled(reason, now), b.version()); }
        return new CancellationEffects(active.stream().map(b -> new BookingRef(b.id(), b.memberId(), b.dogId(), b.paidWithPack())).toList(),
                waiting.stream().map(DemoClassBookings::waitlist).toList());
    }
    /** Adds one fictional registrant (waitlisted entries get the next FIFO position) and refreshes the class counters. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String book(String classId, String memberId, String dogId, boolean paidWithPack, boolean waitlisted) {
        var active = bookings.forClass(classId, DemoClassBooking.State.ACTIVE); var waiting = bookings.forClass(classId, DemoClassBooking.State.WAITLISTED);
        if (active.stream().anyMatch(b -> b.dogId().equals(dogId)) || waiting.stream().anyMatch(b -> b.dogId().equals(dogId))) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.ALREADY_REGISTERED);
        }
        var now = clock.instant();
        var saved = bookings.insert(new DemoClassBooking(UUID.randomUUID().toString(), TenantContext.require(), classId, memberId, dogId,
                waitlisted ? DemoClassBooking.State.WAITLISTED : DemoClassBooking.State.ACTIVE, !waitlisted && paidWithPack,
                waitlisted ? waiting.size() + 1 : null, null, null, 0L, now, now));
        sessions.bookingCounters(classId, active.size() + (waitlisted ? 0 : 1), waiting.size() + (waitlisted ? 1 : 0));
        return saved.id();
    }
    private static WaitlistRef waitlist(DemoClassBooking b) { return new WaitlistRef(b.id(), b.memberId(), b.dogId()); }
}
