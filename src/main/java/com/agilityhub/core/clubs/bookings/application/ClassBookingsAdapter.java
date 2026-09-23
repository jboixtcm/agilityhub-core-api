package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import java.util.*;
import org.springframework.transaction.annotation.*;

/**
 * The real S06 {@link ClassBookingsPort} (E4-T03 contract): live bookings (ACTIVE + PAYMENT_PENDING), the live
 * waiting list (empty until E5-T03 creates entries) and `cancelAllByClub` inside the S06 transaction.
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
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public CancellationEffects cancelAllByClub(String classId, String reason, String actorAccountId) {
        var result = cancellations.cancelByClub(classId, reason, null, actorAccountId);
        return new CancellationEffects(result.bookings().stream().map(ClassBookingsAdapter::ref).toList(), result.waitlist().stream().map(ClassBookingsAdapter::ref).toList());
    }
    private static BookingRef ref(Booking b) { return new BookingRef(b.id(), b.memberId(), b.dogId(), b.packMovementId() != null); }
    private static WaitlistRef ref(WaitlistEntry e) { return new WaitlistRef(e.id(), e.memberId(), e.dogId()); }
}
