package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Outbox publication inside the booking transaction (S08 §7); the event origin is the actor's (R-08-19). */
@Service
public class BookingEvents {
    private final EventPublisher publisher; private final AuditActorProvider actors; private final Clock clock;
    public BookingEvents(EventPublisher publisher, AuditActorProvider actors, Clock clock) { this.publisher = publisher; this.actors = actors; this.clock = clock; }
    public String publish(BookingEvent.Kind kind, String aggregateId, Map<String, Object> payload, BookingActor actor) {
        return publisher.publish(new BookingEvent(kind, TenantContext.require(), aggregateId, clock.instant(), payload,
                actor.isSystem() ? null : actor.accountId(), actor.impersonatedMemberId(), origin(actor)));
    }
    /** S15 R-15-12b `ClassBelowMinimum` (catalog payload `classId, countedDogs, minDogs`); N-54 is E5-T05. */
    public String belowMinimum(String classId, int countedDogs, int minDogs, BookingActor actor) {
        return publisher.publish(new SchedulerEvent(SchedulerEvent.Kind.ClassBelowMinimum, TenantContext.require(), classId, clock.instant(),
                Map.of("classId", classId, "countedDogs", countedDogs, "minDogs", minDogs), actor.isSystem() ? null : actor.accountId(),
                actor.impersonatedMemberId(), origin(actor)));
    }
    private DomainEvent.Origin origin(BookingActor actor) {
        return switch (actor.origin()) {
            case APP -> DomainEvent.Origin.APP; case BACKOFFICE -> DomainEvent.Origin.BACKOFFICE;
            case INSTRUCTOR -> DomainEvent.Origin.INSTRUCTOR; case SYSTEM -> DomainEvent.Origin.SYSTEM;
        };
    }
    /** R-08-05: a rejected attempt of a blocked member is not audited, only logged with the request's traceId. */
    <T> T loggingBlocked(String operation, String classSessionId, java.util.function.Supplier<T> work) {
        try { return work.get(); }
        catch (com.agilityhub.core.shared.domain.ApiException rejected) {
            if (rejected.code() == com.agilityhub.core.shared.domain.ErrorCode.BOOKING_BLOCKED) {
                org.slf4j.LoggerFactory.getLogger(BookingEvents.class).info("Booking blocked operation={} class={} account={} traceId={}", operation, classSessionId,
                        actors.current().accountId(), actors.current().traceId());
            }
            throw rejected;
        }
    }
}
