package com.agilityhub.core.clubs.dashboard.application.ports;

import java.time.Instant;
import java.util.List;

public interface ClassOccupancyQuery {
    /** Bulk projection restricted to the club and half-open club-local week. */
    List<Session> sessions(String clubId, Instant from, Instant until);
    record Session(Instant startsAt, String state, int capacity, int booked, int waiting) { }
}
