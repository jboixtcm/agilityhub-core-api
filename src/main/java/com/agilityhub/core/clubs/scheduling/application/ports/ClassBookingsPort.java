package com.agilityhub.core.clubs.scheduling.application.ports;

import java.util.List;
import org.springframework.transaction.annotation.*;

/** S08 owns bookings, packs, waitlist and holds. Mutations join the scheduling transaction.
 * Booking writers must lock the ClassSession before rechecking ACTIVE and updating counters. */
public interface ClassBookingsPort {
    record BookingRef(String bookingId, String memberId, String dogId, boolean paidWithPack) { }
    record WaitlistRef(String entryId, String memberId, String dogId) { }
    record CancellationEffects(List<BookingRef> bookings, List<WaitlistRef> waitlist) {
        public CancellationEffects { bookings = List.copyOf(bookings); waitlist = List.copyOf(waitlist); }
    }
    List<BookingRef> activeBookings(String classId);
    List<WaitlistRef> liveWaitlist(String classId);
    /** Resolve retained entries, including CANCELLED, for durable outbox delivery. */
    List<WaitlistRef> waitlistEntries(List<String> entryIds);
    @Transactional(propagation = Propagation.MANDATORY)
    CancellationEffects cancelAllByClub(String classId, String reason, String actorAccountId);
    default List<String> bookedDogs(String classId) { return activeBookings(classId).stream().map(BookingRef::dogId).toList(); }
}
