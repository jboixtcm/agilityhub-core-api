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
    @Override public Optional<String> ownerOf(String bookingId) { return bookings.findById(bookingId).map(Booking::memberId); }
}
