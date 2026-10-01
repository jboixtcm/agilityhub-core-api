package com.agilityhub.core.shared.application;

import java.util.Optional;

/**
 * Booking ownership check without coupling payments to bookings (bookings already calls payments for PAY_TO_BOOK, S08
 * R-08-18): `POST /checkout-sessions {bookingId}` checks that the booking is the named member's before anything else
 * (E8-T01 round 2).
 */
public interface BookingOwnerAccess {
    /** The owner (`memberId`, the dog's owner) of a class booking of the open tenant; empty when the club has no such booking. */
    Optional<String> ownerOf(String bookingId);
}
