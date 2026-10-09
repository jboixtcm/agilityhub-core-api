package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.platform.application.audit.AuditActor;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingEvents} (S08 §7, R-08-05, R-08-19; S15 R-15-12b): the acting account travels on
 * the event unless the system acts, `ClassBelowMinimum` carries the catalog payload, and only a BOOKING_BLOCKED rejection
 * is logged with the request's actor. The returned outbox ids are read by no caller (BookingCounters.java:36 and every
 * `events.publish` of the bookings services discard them), so they are not asserted.
 */
class BookingEventsSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final EventPublisher publisher = mock(EventPublisher.class);
    final AuditActorProvider actors = mock(AuditActorProvider.class);
    final BookingContext context = mock(BookingContext.class);
    final BookingEvents events = new BookingEvents(publisher, actors, context);
    /** Laura cancelling from the app (BookingActors#member: her first name, no impersonation). */
    final BookingActor laura = new BookingActor("account-laura", "member-laura", "Laura", null, BookingOrigin.APP, ActorRole.MEMBER);

    @Test void T_08_27_anEventCarriesTheActingAccountUnlessTheSystemActs() {
        when(context.now()).thenReturn(NOW);
        try (var tenant = TenantContext.open("club-a")) {
            events.publish(BookingEvent.Kind.BookingCancelled, "booking-1", Map.of("bookingId", "booking-1", "by", "MEMBER"), laura);
            events.publish(BookingEvent.Kind.BookingCancelled, "booking-2", Map.of("bookingId", "booking-2", "by", "SYSTEM"), BookingActor.system());
        }

        var published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, times(2)).publish(published.capture());
        var member = (BookingEvent) published.getAllValues().get(0);
        assertThat(member.actorAccountId()).isEqualTo("account-laura");
        assertThat(member.origin()).isEqualTo(DomainEvent.Origin.APP);
        var system = (BookingEvent) published.getAllValues().get(1);
        assertThat(system.actorAccountId()).isNull();
        assertThat(system.origin()).isEqualTo(DomainEvent.Origin.SYSTEM);
    }

    @Test void T_15_13_classBelowMinimumCarriesTheCancellingMemberAndTheCatalogPayload() {
        when(context.now()).thenReturn(NOW);
        try (var tenant = TenantContext.open("club-a")) { events.belowMinimum("class-a", 2, 3, laura); }

        var published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher).publish(published.capture());
        var event = (SchedulerEvent) published.getValue();
        assertThat(event.kind()).isEqualTo(SchedulerEvent.Kind.ClassBelowMinimum);
        assertThat(event.actorAccountId()).isEqualTo("account-laura");
        assertThat(event.payload()).containsEntry("classId", "class-a").containsEntry("countedDogs", 2).containsEntry("minDogs", 3);
    }

    @Test void T_08_23_onlyABlockedAttemptIsLoggedWithTheRequestActor() {
        when(actors.current()).thenReturn(new AuditActor("account-laura", "Laura Serra", "MEMBER", null, false, "203.0.113.10", "JUnit", "trace-1"));

        assertThatThrownBy(() -> events.<Object>loggingBlocked("hold", "class-a", () -> { throw new ApiException(ErrorCode.BOOKING_BLOCKED); }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BOOKING_BLOCKED));
        // The log line reads the account and the traceId of the request.
        verify(actors, times(2)).current();
        clearInvocations(actors);

        assertThatThrownBy(() -> events.<Object>loggingBlocked("hold", "class-a", () -> { throw new ApiException(ErrorCode.CLASS_FULL); }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CLASS_FULL));
        verify(actors, never()).current();
        assertThat(events.loggingBlocked("hold", "class-a", () -> List.of("hold-1"))).containsExactly("hold-1");
    }
}
