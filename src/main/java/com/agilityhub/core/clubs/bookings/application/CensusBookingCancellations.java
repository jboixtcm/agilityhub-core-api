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
    @Override public List<LifecycleCancellation> inside(String memberId, LocalDate from, LocalDate to, boolean cancel, boolean leave) {
        var result = new ArrayList<LifecycleCancellation>();
        for (var b : bookings.liveForMember(memberId, context.now())) {
            var date = b.classStartsAt().atZone(context.zone()).toLocalDate();
            if (date.isBefore(from) || to != null && date.isAfter(to)) { continue; }
            if (cancel) { cancellations.cancelBySystem(b.id(), leave ? BookingCancelReason.LEAVE : BookingCancelReason.INACTIVITY); }
            result.add(new LifecycleCancellation("CLASS", b.id(), date));
        }
        if (context.enabled(Module.WAITLIST)) {
            var entries = waitlist.liveForMember(memberId).stream().filter(e -> {
                var date = e.classStartsAt().atZone(context.zone()).toLocalDate();
                return !date.isBefore(from) && (to == null || !date.isAfter(to));
            }).toList();
            if (cancel) { waiting.cancelEntries(entries, leave ? WaitlistCancelReason.LEAVE : WaitlistCancelReason.INACTIVITY); }
            entries.forEach(e -> result.add(new LifecycleCancellation("WAITLIST", e.id(), e.classStartsAt().atZone(context.zone()).toLocalDate())));
        }
        return List.copyOf(result);
    }
}
