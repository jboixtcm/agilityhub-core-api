package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * S08's side of the S15 R-15-14 P4 reminders (the process is `clubs.common.application.RemindersJob`): the ACTIVE class
 * bookings without a reminder whose class starts soon, and the `reminderSentAt` mark. `memberId` is the dog's owner (S08).
 */
@Service
public class ClassReminders {
    /** One class booking as P4 reads it. */
    public record Reminder(String bookingId, String memberId, String dogId, Instant classStartsAt, Instant bookedAt) { }
    private final BookingRepository bookings;
    public ClassReminders(BookingRepository bookings) { this.bookings = bookings; }

    public List<Reminder> scope(Instant after, Instant until) { return bookings.reminderScope(after, until).stream().map(ClassReminders::view).toList(); }
    public Optional<Reminder> current(String bookingId) {
        return bookings.findById(bookingId).filter(b -> b.state() == BookingState.ACTIVE && b.reminderSentAt() == null).map(ClassReminders::view);
    }
    public boolean markSent(String bookingId, Instant classStartsAt, Instant at) { return bookings.markReminderSent(bookingId, classStartsAt, at); }

    private static Reminder view(Booking b) { return new Reminder(b.id(), b.memberId(), b.dogId(), b.classStartsAt(), b.bookedAt()); }
}
