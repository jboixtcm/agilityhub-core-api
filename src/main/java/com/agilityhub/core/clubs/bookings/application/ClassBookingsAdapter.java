package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import java.util.*;
import org.springframework.transaction.annotation.*;

/**
 * The real S06 {@link ClassBookingsPort} (E4-T03 contract): live bookings (ACTIVE + PAYMENT_PENDING), the live
 * waiting list (ACTIVE + NOTIFIED entries) and `cancelAllByClub` inside the S06 transaction (bookings and entries).
 */
public class ClassBookingsAdapter implements ClassBookingsPort {
    private final BookingRepository bookings; private final WaitlistEntryRepository waitlist; private final BookingCancellationService cancellations;
    public ClassBookingsAdapter(BookingRepository bookings, WaitlistEntryRepository waitlist, BookingCancellationService cancellations) {
        this.bookings = bookings; this.waitlist = waitlist; this.cancellations = cancellations;
    }
    @Override public List<BookingRef> activeBookings(String classId) {
        return bookings.forClass(classId, BookingRepository.LIVE).stream().map(ClassBookingsAdapter::ref).toList();
    }
    @Override public List<WaitlistRef> liveWaitlist(String classId) { return waitlist.live(classId).stream().map(ClassBookingsAdapter::ref).toList(); }
    @Override public List<WaitlistRef> waitlistEntries(List<String> entryIds) {
        var order = new HashMap<String, Integer>(); for (int i = 0; i < entryIds.size(); i++) { order.putIfAbsent(entryIds.get(i), i); }
        return waitlist.byIds(new LinkedHashSet<>(entryIds)).stream().sorted(Comparator.comparing(e -> order.get(e.id()))).map(ClassBookingsAdapter::ref).toList();
    }
    @Override public List<BookingRef> bookings(List<String> bookingIds) {
        var order = new HashMap<String, Integer>(); for (int i = 0; i < bookingIds.size(); i++) { order.putIfAbsent(bookingIds.get(i), i); }
        return bookings.byIds(new LinkedHashSet<>(bookingIds)).stream().sorted(Comparator.comparing(b -> order.get(b.id()))).map(ClassBookingsAdapter::ref).toList();
    }
    @Override public List<BookingRef> clubCancelled(String classId) {
        return bookings.forClass(classId, List.of(com.agilityhub.core.clubs.bookings.domain.BookingState.CANCELLED_BY_CLUB)).stream().map(ClassBookingsAdapter::ref).toList();
    }
    @Override public Map<String, List<BookingRef>> activeBookingsByClass(Collection<String> classIds) {
        return byClass(classIds, bookings.forClasses(classIds, BookingRepository.LIVE));
    }
    @Override public Map<String, List<BookingRef>> clubCancelledByClass(Collection<String> classIds) {
        return byClass(classIds, bookings.forClasses(classIds, List.of(com.agilityhub.core.clubs.bookings.domain.BookingState.CANCELLED_BY_CLUB)));
    }
    private static Map<String, List<BookingRef>> byClass(Collection<String> classIds, List<Booking> found) {
        var result = new LinkedHashMap<String, List<BookingRef>>(); classIds.forEach(id -> result.put(id, new ArrayList<>()));
        found.forEach(b -> result.computeIfAbsent(b.classSessionId(), id -> new ArrayList<>()).add(ref(b)));
        return result;
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public CancellationEffects cancelAllByClub(String classId, String reason, String actorAccountId) {
        var result = cancellations.cancelByClub(classId, reason, null, actorAccountId);
        return new CancellationEffects(result.bookings().stream().map(ClassBookingsAdapter::ref).toList(), result.waitlist().stream().map(ClassBookingsAdapter::ref).toList());
    }
    private static BookingRef ref(Booking b) { return new BookingRef(b.id(), b.memberId(), b.dogId(), b.packMovementId() != null); }
    private static WaitlistRef ref(WaitlistEntry e) { return new WaitlistRef(e.id(), e.memberId(), e.dogId()); }
}
