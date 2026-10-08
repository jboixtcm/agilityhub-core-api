package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.census.application.ports.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.platform.application.Module;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** E90: bookings implements a census-owned contract; no reverse context dependency. */
@Service
public class CensusBookingCancellations implements BookingCancellationPort {
    private final BookingRepository bookings; private final WaitlistEntryRepository waitlist;
    private final BookingCancellationService cancellations; private final WaitlistService waiting; private final BookingContext context;
    public CensusBookingCancellations(BookingRepository bookings, WaitlistEntryRepository waitlist,
            BookingCancellationService cancellations, WaitlistService waiting, BookingContext context) {
        this.bookings = bookings; this.waitlist = waitlist; this.cancellations = cancellations; this.waiting = waiting; this.context = context;
    }
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess classes;
    @org.springframework.beans.factory.annotation.Autowired private SeatLockRepository locks;
    @org.springframework.beans.factory.annotation.Autowired private BookingTransactions transactions;
    @Override public List<LifecycleCancellation> inside(String memberId, LocalDate from, LocalDate to, boolean cancel, boolean leave) {
        var live = bookings.liveForMember(memberId);
        var entries = context.enabled(Module.WAITLIST) ? waitlist.liveForMember(memberId) : List.<WaitlistEntry>of();
        var ids = new TreeSet<String>(); live.forEach(b -> ids.add(b.classSessionId())); entries.forEach(e -> ids.add(e.classSessionId()));
        if (cancel) { return transactions.write(ids, () -> select(live, entries, ids, from, to, true, leave)); }
        return select(live, entries, ids, from, to, false, leave);
    }
    private List<LifecycleCancellation> select(List<Booking> live, List<WaitlistEntry> entries, Set<String> ids,
            LocalDate from, LocalDate to, boolean cancel, boolean leave) {
        var result = new ArrayList<LifecycleCancellation>();
        for (String id : ids) {
            if (cancel) { locks.lock(id); }
            var session = cancel ? classes.lock(id) : classes.require(id);
            var date = session.startsAt().atZone(context.zone()).toLocalDate();
            if (!session.startsAt().isAfter(context.now()) || date.isBefore(from) || to != null && date.isAfter(to)) { continue; }
            for (var initial : live.stream().filter(b -> id.equals(b.classSessionId())).toList()) {
                var b = bookings.require(initial.id());
                if (!BookingRepository.LIVE.contains(b.state())) { continue; }
                if (cancel) { cancellations.cancelBySystem(b.id(), leave ? BookingCancelReason.LEAVE : BookingCancelReason.INACTIVITY); }
                result.add(new LifecycleCancellation("CLASS", b.id(), date));
            }
            for (var initial : entries.stream().filter(e -> id.equals(e.classSessionId())).toList()) {
                var e = waitlist.findById(initial.id()).orElseThrow();
                if (!WaitlistEntryRepository.LIVE.contains(e.state())) { continue; }
                if (cancel) { waiting.cancelEntries(List.of(e), leave ? WaitlistCancelReason.LEAVE : WaitlistCancelReason.INACTIVITY); }
                result.add(new LifecycleCancellation("WAITLIST", e.id(), date));
            }
        }
        return List.copyOf(result);
    }
}
