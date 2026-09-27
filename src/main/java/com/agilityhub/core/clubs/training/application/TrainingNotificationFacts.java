package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.BookingNotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.BookingRelevancePort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S09 notices explained to the S11 engine (E7-T02; replaces `TrainingNotifications`, rules unchanged): N-06
 * `TrainingBooked`; N-47 `TrainingBooked/Cancelled` made by the club (`origin = BACKOFFICE` or `by = ADMIN`), with the
 * cancellation note as `admin_text`; N-07 `TrainingCancelled` otherwise, except `cancelReason = MEMBER_LEFT` (S09); the
 * N-13 reminder of a training booking (`kind = TRAINING`). `time` = «8:30–9:00» in the club's time zone. Also the
 * {@link BookingRelevancePort} of R-11-16 for both kinds of booking (the class one through S08's facts).
 */
@Service
public class TrainingNotificationFacts implements NotificationFactsPort, BookingRelevancePort {
    private static final Set<String> TYPES = Set.of("TrainingBooked", "TrainingCancelled", "ReminderDue");
    private final TrainingBookingRepository bookings; private final TrainingBookingService service; private final BookingNotificationFacts classBookings;

    public TrainingNotificationFacts(TrainingBookingRepository bookings, TrainingBookingService service, BookingNotificationFacts classBookings) {
        this.bookings = bookings; this.service = service; this.classBookings = classBookings;
    }

    @Override public Set<String> eventTypes() { return TYPES; }

    /** Which catalog code (if any) a training event produces. */
    static boolean applies(String code, NotificationTrigger trigger) {
        boolean booked = "TrainingBooked".equals(trigger.type());
        boolean club = "BACKOFFICE".equals(trigger.text("origin")) || "ADMIN".equals(trigger.text("by"));
        return switch (code) {
            case "N-06" -> booked; // as E5-T04: every booking, also the club's (which gets N-47 too)
            case "N-47" -> club;
            case "N-07" -> !booked && !club && !"MEMBER_LEFT".equals(trigger.text("cancelReason"));
            default -> false;
        };
    }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        boolean reminder = "ReminderDue".equals(trigger.type());
        if (reminder ? trigger.text("trainingBookingId") == null : !applies(code, trigger)) { return Optional.empty(); }
        String id = reminder ? trigger.text("trainingBookingId") : Objects.requireNonNullElse(trigger.text("trainingBookingId"), trigger.aggregateId());
        var booking = bookings.findById(id).orElse(null);
        if (booking == null) { return Optional.empty(); }
        var builder = NotificationFacts.builder().subject(NotificationSubject.trainingBooking(booking.id()))
                .member(new NotificationFacts.MemberSubject(booking.memberId(), booking.dogId(), Map.of("entityId", booking.id()), NotificationSubject.trainingBooking(booking.id())))
                .value("date", booking.startsAt()).value("time", new NotificationValues.TimeRange(booking.startsAt(), booking.endsAt()))
                .value("ring_name", Objects.toString(service.ringNames().get(booking.ringId()), ""));
        if (reminder) { return Optional.of(builder.value("kind", "TRAINING").build()); }
        if (code.equals("N-47")) {
            String note = "TrainingCancelled".equals(trigger.type()) ? booking.cancelNote() : null;
            builder.value("change", "TrainingBooked".equals(trigger.type()) ? "BOOKED" : "CANCELLED")
                    .value("has_admin_text", note != null && !note.isBlank() ? "true" : "false").value("admin_text", note == null ? "" : note);
        }
        return Optional.of(builder.build());
    }

    /** R-11-16: the booking of a reminder is still `ACTIVE` and starts after now. */
    @Override public boolean stillActive(String bookingId, String trainingBookingId, Instant now) {
        if (trainingBookingId != null) {
            return bookings.findById(trainingBookingId).filter(b -> b.state() == TrainingBookingState.ACTIVE && b.startsAt().isAfter(now)).isPresent();
        }
        return bookingId != null && classBookings.activeAndFuture(bookingId, now);
    }
}
