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
    /**
     * R-09-13: `$inc` the ring-slot sequences of every slot of the ring a booking can still take (now → end of the
     * booking window) inside the caller's transaction, before {@link #futureActive}: a concurrent booking of the ring
     * then conflicts with the catalog change in Mongo instead of both committing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockBookableSlots(String ringId);
    /** Each still ACTIVE booking → CANCELLED_BY_CLUB with `cancelReason = RING_NOT_RESERVABLE`. */
    @Transactional(propagation = Propagation.MANDATORY)
    void cancelNotReservable(List<String> bookingIds);

    RingTrainingBookings NONE = new RingTrainingBookings() {
        @Override public List<Booking> futureActive(String ringId) { return List.of(); }
        @Override public void lockBookableSlots(String ringId) { }
        @Override public void cancelNotReservable(List<String> bookingIds) { }
    };
}
