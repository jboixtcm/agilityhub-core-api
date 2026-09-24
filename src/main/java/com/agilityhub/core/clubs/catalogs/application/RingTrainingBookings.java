package com.agilityhub.core.clubs.catalogs.application;

import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S05 R-05-08 → S09 R-09-13: the live training bookings of a ring that stops being reservable (`allowsFreeTraining`
 * off, or the ring deactivated). S09 supplies the real adapter; without it a ring has none. The cancellation joins the
 * caller's catalog transaction.
 */
public interface RingTrainingBookings {
    /** The same shape as the `RING_HAS_BOOKINGS{bookings[]}` details of S06/S09. */
    record Booking(String id, String ringId, Instant from, Instant to, String memberName, String dogName) { }
    /** ACTIVE bookings of the ring that start after now. */
    List<Booking> futureActive(String ringId);
    /** Each still ACTIVE booking → CANCELLED_BY_CLUB with `cancelReason = RING_NOT_RESERVABLE`. */
    @Transactional(propagation = Propagation.MANDATORY)
    void cancelNotReservable(List<String> bookingIds);

    RingTrainingBookings NONE = new RingTrainingBookings() {
        @Override public List<Booking> futureActive(String ringId) { return List.of(); }
        @Override public void cancelNotReservable(List<String> bookingIds) { }
    };
}
