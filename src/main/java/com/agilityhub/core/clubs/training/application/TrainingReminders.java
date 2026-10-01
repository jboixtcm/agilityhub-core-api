package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.common.application.ports.ReminderSource;
import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * S09's side of the S15 R-15-14 P4 reminders. A training booking keeps the member who booked (`memberId`, R-09-06), which in
 * a family group can differ from the dog's owner: P4 reminds the owner (R-15-14 «propietari del gos»), read from the census.
 * The booking moment is `createdAt`. A dog the census no longer knows keeps the booker.
 */
@Component
public class TrainingReminders implements ReminderSource {
    private final TrainingBookingRepository bookings; private final TrainingMemberAccess census;
    public TrainingReminders(TrainingBookingRepository bookings, TrainingMemberAccess census) { this.bookings = bookings; this.census = census; }

    @Override public Kind kind() { return Kind.TRAINING; }
    @Override public List<Candidate> scope(Instant after, Instant until) { return candidates(bookings.reminderScope(after, until)); }
    @Override public Optional<Candidate> current(String id) {
        return bookings.findById(id).filter(b -> b.state() == TrainingBookingState.ACTIVE && b.reminderSentAt() == null)
                .flatMap(b -> candidates(List.of(b)).stream().findFirst());
    }
    @Override public boolean markSent(String id, Instant startsAt, Instant at) { return bookings.markReminderSent(id, startsAt, at); }

    private List<Candidate> candidates(List<TrainingBooking> found) {
        if (found.isEmpty()) { return List.of(); }
        var owners = new HashMap<String, String>();
        census.dogs(found.stream().map(TrainingBooking::dogId).distinct().toList()).forEach(dog -> owners.put(dog.id(), dog.memberId()));
        return found.stream().map(b -> new Candidate(b.id(), owner(owners, b), b.dogId(), b.startsAt(), b.createdAt())).toList();
    }
    private static String owner(Map<String, String> owners, TrainingBooking booking) {
        String owner = owners.get(booking.dogId());
        return owner == null ? booking.memberId() : owner;
    }
}
