package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.shared.application.BookingOwnerAccess;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** S08's side of {@link BookingOwnerAccess}: a booking of the open tenant and its owner (`Booking.memberId`), read-only. */
@Service
public class BookingOwners implements BookingOwnerAccess {
    private final BookingRepository bookings;
    public BookingOwners(BookingRepository bookings) { this.bookings = bookings; }
    @Override public boolean cancelled(String id) {
        return bookings.findById(id).map(b -> b.state() == com.agilityhub.core.clubs.bookings.domain.BookingState.CANCELLED).orElse(false);
    }
    @Override public Optional<Checkout> checkout(String id) {
        return bookings.findById(id).filter(b -> b.state() == com.agilityhub.core.clubs.bookings.domain.BookingState.PAYMENT_PENDING)
                .filter(b -> b.charge() != null && b.charge().checkoutUrl() != null).map(b -> new Checkout(b.charge().checkoutSessionId(), b.charge().checkoutUrl()));
    }
    @Override public Optional<String> ownerOf(String bookingId) { return bookings.findById(bookingId).map(Booking::memberId); }
}
