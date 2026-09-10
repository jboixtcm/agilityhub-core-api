package com.agilityhub.core.clubs.dashboard.application.ports;

import java.time.Instant;
import java.util.List;

public interface TrainingBookingsQuery {
    List<Booking> bookings(String clubId, Instant from, Instant until);
    record Booking(String memberId, Instant startsAt, String status) { }
}
