package com.agilityhub.core.clubs.dashboard.application.ports;

import java.time.Instant;
import java.util.Set;

public interface BookingActivity {
    /** Distinct dog IDs with ACTIVE/CANCELLED_LATE bookings by class start, never booking creation. */
    Set<String> dogsWithBooking(String clubId, Instant from, Instant until);

    /** Active bookings grouped by the dog's current level for S06 week coverage; E5 supplies the adapter. */
    default java.util.Map<String, Integer> activeBookingsByLevel(String clubId, Instant from, Instant until) { return java.util.Map.of(); }
}
