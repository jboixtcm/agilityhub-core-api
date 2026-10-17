package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ForeignEvent;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingConsumers}' outbox handlers (S08 §7): each `bookings.*` bean is a handler of
 * its event type whose delivery reaches the consumer — {@link BookingConsumers#handle} for the S06/S03/S12 envelopes,
 * {@link BookingConsumers#modulesChanged} directly for `ClubModulesChanged` (its envelope does not serialise its type).
 */
class BookingConsumersSurvivors3Test {
    static final Instant AT = Instant.parse("2026-10-05T08:00:00Z");

    final BookingConsumers target = mock(BookingConsumers.class);
    final BookingConsumers.Handlers handlers = new BookingConsumers.Handlers();

    @Test void T_08_46_theClassSessionUpdatedAndDogLevelChangedHandlersDelegateToTheConsumer() throws Exception {
        // ClassSessionService#update publishes {classId, diff, bookedCount}; R-08-21: the bookings stay, only the copies change.
        assertDelivered(handlers.classUpdated(target), event("ClassSessionUpdated", "ClassSession", "class-1",
                Map.of("classId", "class-1", "diff", Map.of(), "bookedCount", 0)));
        // R-08-04/R-08-21: a level change after booking keeps the booking (nothing to do, but the event is consumed).
        assertDelivered(handlers.dogLevel(target), event("DogLevelChanged", "Dog", "dog-duna", Map.of("dogId", "dog-duna")));
    }

    @Test void T_08_25_theClassCancelledByClubHandlerDelegatesToTheConsumer() throws Exception {
        assertDelivered(handlers.classCancelled(target), event("ClassCancelledByClub", "ClassSession", "class-1", Map.of("classId", "class-1")));
    }

    @Test void T_08_23_theBookingBlockChangedHandlerDelegatesToTheConsumer() throws Exception {
        assertDelivered(handlers.bookingBlock(target), event("BookingBlockChanged", "Member", "member-laura", Map.of("memberId", "member-laura")));
    }

    @Test void T_08_24_theUpfrontPaymentHandlersDelegateToTheConsumer() throws Exception {
        assertDelivered(handlers.paid(target), event("UpfrontPaymentSucceeded", "UpfrontPayment", "payment-1", Map.of("bookingId", "booking-1")));
        assertDelivered(handlers.failed(target), event("UpfrontPaymentFailed", "UpfrontPayment", "payment-2", Map.of("bookingId", "booking-2")));
    }

    @Test void E11_T06_theClubModulesChangedHandlerCallsTheModulesConsumerDirectly() throws Exception {
        // ClubSettingsService#updateModule: {diff: {modules: {before, after}}}; the envelope carries no type.
        var event = new ForeignEvent(null, null, "club-a", "Club", "club-a", AT,
                Map.of("diff", Map.of("modules", Map.of("before", List.of("FAQ"), "after", List.of()))), "account-admin", null, DomainEvent.Origin.BACKOFFICE);
        var handler = handlers.modules(target);

        assertThat(handler.eventType()).isEqualTo("ClubModulesChanged");
        assertThat(handler.eventClass()).isEqualTo(ForeignEvent.class);
        handler.handle("event-1", event);
        verify(target).modulesChanged(event);
        verify(target, never()).handle(any());
    }

    private void assertDelivered(DomainEventHandler<ForeignEvent> handler, ForeignEvent event) throws Exception {
        assertThat(handler.eventType()).isEqualTo(event.type());
        assertThat(handler.eventClass()).isEqualTo(ForeignEvent.class);
        handler.handle("event-" + event.type(), event);
        verify(target).handle(event);
    }

    private static ForeignEvent event(String type, String aggregateType, String aggregateId, Map<String, Object> payload) {
        return new ForeignEvent(type, null, "club-a", aggregateType, aggregateId, AT, payload, "account-admin", null, DomainEvent.Origin.BACKOFFICE);
    }
}
