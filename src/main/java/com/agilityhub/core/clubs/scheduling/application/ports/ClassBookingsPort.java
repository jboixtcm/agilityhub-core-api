package com.agilityhub.core.clubs.scheduling.application.ports;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** Resolve retained bookings in any state, in the order of the ids (S15 N-16 and the D1 `notified` names). */
    default List<BookingRef> bookings(List<String> bookingIds) { return List.of(); }
    /** The bookings a club cancellation of the class affected (CANCELLED_BY_CLUB), for the D1 `notified` names. */
    default List<BookingRef> clubCancelled(String classId) { return List.of(); }
    /** {@link #activeBookings(String)} of many classes in one read (the D1 card, E5-T10); every requested class is a key. */
    default Map<String, List<BookingRef>> activeBookingsByClass(Collection<String> classIds) {
        var result = new LinkedHashMap<String, List<BookingRef>>(); classIds.forEach(id -> result.put(id, activeBookings(id))); return result;
    }
    /** {@link #clubCancelled(String)} of many classes in one read (the D1 card, E5-T10); every requested class is a key. */
    default Map<String, List<BookingRef>> clubCancelledByClass(Collection<String> classIds) {
        var result = new LinkedHashMap<String, List<BookingRef>>(); classIds.forEach(id -> result.put(id, clubCancelled(id))); return result;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    CancellationEffects cancelAllByClub(String classId, String reason, String actorAccountId);
    default List<String> bookedDogs(String classId) { return activeBookings(classId).stream().map(BookingRef::dogId).toList(); }
}
