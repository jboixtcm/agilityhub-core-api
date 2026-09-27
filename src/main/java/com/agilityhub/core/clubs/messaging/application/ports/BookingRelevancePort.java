package com.agilityhub.core.clubs.messaging.application.ports;

import java.time.Instant;

/** S11 R-11-16 (N-13): whether the class booking or the training booking of a reminder is still `ACTIVE` and starts after `now`. */
public interface BookingRelevancePort {
    boolean stillActive(String bookingId, String trainingBookingId, Instant now);
}
