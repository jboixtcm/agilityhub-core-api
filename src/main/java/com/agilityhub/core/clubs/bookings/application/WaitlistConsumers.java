package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.util.Objects;
import org.springframework.context.annotation.*;

/**
 * S08 §7 consumed waiting-list events, idempotent by state (a redelivery finds nothing left to offer):
 * `waitlist.SeatReleased` → {@link WaitlistService#offerSeats} when `notifyWaitlist` is true (R-08-13/14);
 * `waitlist.WaitlistExpired` (S15 P6, FIFO) → {@link WaitlistService#offerNext}.
 */
@Configuration(proxyBeanMethods = false)
public class WaitlistConsumers {
    @Bean("waitlist.SeatReleased") DomainEventHandler<BookingEvent> seatReleased(WaitlistService waitlist) {
        return new DomainEventHandler<>() {
            public String eventType() { return "SeatReleased"; } public Class<BookingEvent> eventClass() { return BookingEvent.class; }
            public void handle(String id, BookingEvent event) {
                if (!Boolean.TRUE.equals(event.payload().get("notifyWaitlist"))) { return; }
                try (var tenant = TenantContext.open(event.clubId())) {
                    waitlist.offerSeats(Objects.toString(event.payload().getOrDefault("classId", event.aggregateId())),
                            event.payload().get("freeSeats") instanceof Number seats ? seats.intValue() : 0);
                }
            }
        };
    }
    @Bean("waitlist.WaitlistExpired") DomainEventHandler<SchedulerEvent> waitlistExpired(WaitlistService waitlist, WaitlistEntryRepository entries) {
        return new DomainEventHandler<>() {
            public String eventType() { return "WaitlistExpired"; } public Class<SchedulerEvent> eventClass() { return SchedulerEvent.class; }
            public void handle(String id, SchedulerEvent event) {
                try (var tenant = TenantContext.open(event.clubId())) {
                    var entryId = Objects.toString(event.payload().getOrDefault("entryId", event.aggregateId()), null);
                    // `classId` is the S15 §7 payload addition; the catalog row only has `entryId`.
                    var classId = event.payload().get("classId") != null ? event.payload().get("classId").toString()
                            : entries.findById(entryId).map(WaitlistEntry::classSessionId).orElse(null);
                    if (classId != null) { waitlist.offerNext(classId, entryId); }
                }
            }
        };
    }
    /**
     * S15 R-15-12b: an expired FIFO offer that leaves the class as it was re-checks the minimum inside its own
     * transaction; `ClassBelowMinimum` (→ N-54) is emitted only while `risk.lowAlertSentAt` is unset, never for a class
     * that has started, and nothing else changes.
     */
    @Bean("alerts.WaitlistExpired") DomainEventHandler<SchedulerEvent> lowAlertAfterExpiry(BookingTransactions transactions, SeatLockRepository locks,
            BookingCounters counters, WaitlistEntryRepository entries) {
        return new DomainEventHandler<>() {
            public String eventType() { return "WaitlistExpired"; } public Class<SchedulerEvent> eventClass() { return SchedulerEvent.class; }
            public void handle(String id, SchedulerEvent event) {
                try (var tenant = TenantContext.open(event.clubId())) {
                    var entryId = Objects.toString(event.payload().getOrDefault("entryId", event.aggregateId()), null);
                    var classId = event.payload().get("classId") != null ? event.payload().get("classId").toString()
                            : entries.findById(entryId).map(WaitlistEntry::classSessionId).orElse(null);
                    if (classId == null) { return; }
                    transactions.write(java.util.List.of(classId), () -> { locks.lock(classId); counters.recount(classId, true, BookingActor.system()); return null; });
                }
            }
        };
    }
}
