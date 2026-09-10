package com.agilityhub.core.clubs.dashboard.application.ports;

import java.time.Instant;
import java.util.Set;

public interface BookingActivity {
    /** Distinct dog IDs with ACTIVE/CANCELLED_LATE bookings by class start, never booking creation. */
    Set<String> dogsWithBooking(String clubId, Instant from, Instant until);
}
