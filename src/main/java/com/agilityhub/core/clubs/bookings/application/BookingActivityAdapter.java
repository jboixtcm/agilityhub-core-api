package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.dashboard.application.ports.BookingActivity;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;

/** The real S14 {@link BookingActivity} (E3-T04 contract): by class start, never by booking creation. */
public class BookingActivityAdapter implements BookingActivity {
    private final BookingRepository bookings; private final BookingMemberAccess census;
    public BookingActivityAdapter(BookingRepository bookings, BookingMemberAccess census) { this.bookings = bookings; this.census = census; }
    @Override public Set<String> dogsWithBooking(String clubId, Instant from, Instant until) {
        tenant(clubId);
        var result = new TreeSet<String>();
        bookings.startingBetween(from, until, List.of(BookingState.ACTIVE, BookingState.CANCELLED_LATE)).forEach(b -> result.add(b.dogId()));
        return result;
    }
    /** ACTIVE bookings grouped by the dog's current level, for the S06 week coverage. */
    @Override public Map<String, Integer> activeBookingsByLevel(String clubId, Instant from, Instant until) {
        tenant(clubId);
        var active = bookings.startingBetween(from, until, List.of(BookingState.ACTIVE));
        var levels = new HashMap<String, String>();
        census.dogs(active.stream().map(b -> b.dogId()).distinct().toList()).forEach(d -> { if (d.levelId() != null) { levels.put(d.id(), d.levelId()); } });
        var result = new TreeMap<String, Integer>();
        active.forEach(b -> { var level = levels.get(b.dogId()); if (level != null) { result.merge(level, 1, Integer::sum); } });
        return result;
    }
    private static void tenant(String clubId) { if (!TenantContext.require().equals(clubId)) { throw new ApiException(ErrorCode.TENANT_MISMATCH); } }
}
