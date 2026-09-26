package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.ForeignEvent;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

/**
 * S10 §7 consumed events, idempotent by construction (re-applying the same event changes nothing):
 * <ul>
 * <li>`ClassSessionUpdated` → the denormalised `classDate`/`classStartsAt`/`classEndsAt` of the class's attendances;</li>
 * <li>`BookingCancelled{by ∈ MEMBER, ADMIN, SYSTEM}` → while S08 still refuses to cancel a marked booking (R-08-10,
 * §13-6) this is a contradiction: an existing PRESENT/NO_SHOW mark gets one trail entry (its state, the cancellation
 * instant and account) and a warning in the log; nothing else changes.</li>
 * </ul>
 * `ClassCancelledByClub`, `ClassAutoCancelled` and `DogDeactivated` need nothing here: the bookings become
 * CANCELLED_BY_CLUB and leave the sheet and the metrics by themselves, and a deactivated dog keeps its attendance.
 */
@Service
public class AttendanceConsumers {
    private static final Logger LOG = LoggerFactory.getLogger(AttendanceConsumers.class);
    private final AttendanceRepository attendances; private final InstructorScheduleAccess schedule; private final BookingTransactions transactions;
    public AttendanceConsumers(AttendanceRepository attendances, InstructorScheduleAccess schedule, BookingTransactions transactions) {
        this.attendances = attendances; this.schedule = schedule; this.transactions = transactions;
    }
    public void classUpdated(ForeignEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            String classId = Objects.toString(event.payload().getOrDefault("classId", event.aggregateId()));
            schedule.find(classId).ifPresent(c -> transactions.write(List.of(classId), () -> attendances.refreshClass(classId, c.date(), c.startsAt(), c.endsAt())));
        }
    }
    public void bookingCancelled(BookingEvent event) {
        String by = Objects.toString(event.payload().get("by"), "");
        if (!Set.of("MEMBER", "ADMIN", "SYSTEM").contains(by) || "INSTRUCTOR".equals(Objects.toString(event.payload().get("origin"), ""))) { return; }
        try (var tenant = TenantContext.open(event.clubId())) {
            var mark = attendances.findByBooking(event.aggregateId()).orElse(null);
            if (mark == null || (mark.state() != AttendanceState.PRESENT && mark.state() != AttendanceState.NO_SHOW)) { return; }
            // Mongo keeps milliseconds: the redelivered event must compare equal to the entry the first delivery stored.
            var change = new Attendance.Change(mark.state(), event.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), event.actorAccountId());
            if (mark.history() != null && mark.history().contains(change)) { return; } // a redelivery
            LOG.warn("Booking {} cancelled by {} while its attendance is {} (S10 §7 contradiction; S08 should have refused it)", event.aggregateId(), by, mark.state());
            transactions.write(List.of(mark.classSessionId()), () -> { attendances.appendHistory(mark.bookingId(), change); return null; });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean("attendance.ClassSessionUpdated") DomainEventHandler<ForeignEvent> classUpdated(AttendanceConsumers consumers) {
            return new DomainEventHandler<>() {
                public String eventType() { return "ClassSessionUpdated"; } public Class<ForeignEvent> eventClass() { return ForeignEvent.class; }
                public void handle(String id, ForeignEvent event) { consumers.classUpdated(event); }
            };
        }
        @Bean("attendance.BookingCancelled") DomainEventHandler<BookingEvent> bookingCancelled(AttendanceConsumers consumers) {
            return new DomainEventHandler<>() {
                public String eventType() { return "BookingCancelled"; } public Class<BookingEvent> eventClass() { return BookingEvent.class; }
                public void handle(String id, BookingEvent event) { consumers.bookingCancelled(event); }
            };
        }
    }
}
