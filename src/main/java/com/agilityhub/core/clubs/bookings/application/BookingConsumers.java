package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.shared.application.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;

/**
 * S08 §7 consumed events, idempotent by construction (re-applying the same event changes nothing):
 * `ClassSessionUpdated{startsAt|endsAt}` refreshes the denormalised class times and week of live bookings and live
 * waiting-list entries (R-08-21);
 * `ClassCancelledByClub` only asserts nothing live remains (S06 cancelled synchronously through the port);
 * `DogLevelChanged` and `BookingBlockChanged` have no effect on existing bookings (R-08-21, R-08-05);
 * `UpfrontPaymentSucceeded` / `UpfrontPaymentFailed` with a `bookingId` settle a PAYMENT_PENDING booking (R-08-18).
 */
@Service
public class BookingConsumers {
    private static final Logger LOG = LoggerFactory.getLogger(BookingConsumers.class);
    private final BookingRepository bookings; private final ClassSessionBookingAccess classes; private final BookingContext context;
    private final BookingTransactions transactions; private final SeatLockRepository locks; private final BookingConfirmationService confirmations;
    private final BookingCancellationService cancellations; private final WaitlistEntryRepository waitlist;
    public BookingConsumers(BookingRepository bookings, ClassSessionBookingAccess classes, BookingContext context, BookingTransactions transactions,
            SeatLockRepository locks, BookingConfirmationService confirmations, BookingCancellationService cancellations, WaitlistEntryRepository waitlist) {
        this.bookings = bookings; this.classes = classes; this.context = context; this.transactions = transactions; this.locks = locks;
        this.confirmations = confirmations; this.cancellations = cancellations; this.waitlist = waitlist;
    }
    public void handle(ForeignEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            switch (event.type()) {
                case "ClassSessionUpdated" -> refreshTimes(Objects.toString(event.payload().getOrDefault("classId", event.aggregateId())));
                case "ClassCancelledByClub" -> {
                    var live = bookings.forClass(event.aggregateId(), BookingRepository.LIVE);
                    if (!live.isEmpty()) { LOG.warn("Class {} cancelled by the club still has {} live bookings", event.aggregateId(), live.size()); }
                }
                case "UpfrontPaymentSucceeded" -> booking(event).ifPresent(confirmations::paymentSucceeded);
                case "UpfrontPaymentFailed" -> booking(event).ifPresent(id -> bookings.findById(id)
                        .filter(b -> b.state() == BookingState.PAYMENT_PENDING).ifPresent(b -> cancellations.cancelBySystem(id, BookingCancelReason.PAYMENT_TIMEOUT)));
                default -> { }
            }
        }
    }
    private static Optional<String> booking(ForeignEvent event) { return Optional.ofNullable(event.payload().get("bookingId")).map(Object::toString); }
    /** R-08-21: only the denormalised copies change; the bookings themselves stay valid. */
    void refreshTimes(String classId) {
        var session = classes.find(classId).orElse(null); if (session == null) { return; }
        var weeks = context.weeks(); String key = weeks.week(session.startsAt()).key();
        var stale = bookings.forClass(classId, BookingRepository.LIVE).stream().filter(b -> !Objects.equals(b.classStartsAt(), session.startsAt())
                || !Objects.equals(b.classEndsAt(), session.endsAt()) || !Objects.equals(b.bookingWeekKey(), key)).toList();
        var staleEntries = waitlist.live(classId).stream()
                .filter(e -> !Objects.equals(e.classStartsAt(), session.startsAt()) || !Objects.equals(e.bookingWeekKey(), key)).toList();
        if (stale.isEmpty() && staleEntries.isEmpty()) { return; }
        transactions.write(List.of(classId), () -> {
            locks.lock(classId); var now = context.now();
            for (var initial : stale) {
                var b = bookings.require(initial.id()); if (!BookingRepository.LIVE.contains(b.state())) { continue; }
                bookings.update(new Booking(b.id(), b.clubId(), b.classSessionId(), b.dogId(), b.memberId(), b.state(), b.origin(), b.bookedAt(), b.bookedBy(),
                        session.startsAt(), session.endsAt(), key, b.cancelledAt(), b.cancelledBy(), b.cancelReason(), b.cancelMessage(), b.late(), b.minutesBefore(),
                        b.swapFromBookingId(), b.swapToBookingId(), b.waitlistEntryId(), b.packMovementId(), b.packRefundMovementId(), b.charge(), b.reminderSentAt(),
                        b.version() + 1, b.createdAt(), b.createdByAccountId(), now, b.updatedByAccountId()), b.version());
            }
            for (var initial : staleEntries) {
                var e = waitlist.findById(initial.id()).orElse(null); if (e == null || !WaitlistEntryRepository.LIVE.contains(e.state())) { continue; }
                long version = e.version() == null ? 0L : e.version();
                waitlist.update(new WaitlistEntry(e.id(), e.clubId(), e.classSessionId(), e.dogId(), e.memberId(), e.accountId(), e.joinedAt(), e.state(),
                        e.position(), e.notifiedAt(), e.confirmBy(), e.bookingId(), e.cancelledAt(), e.cancelReason(), session.startsAt(), key,
                        version + 1, e.createdAt(), e.createdByAccountId(), now, e.updatedByAccountId()), version);
            }
            return null;
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean("bookings.ClassSessionUpdated") DomainEventHandler<ForeignEvent> classUpdated(BookingConsumers c) { return handler("ClassSessionUpdated", c); }
        @Bean("bookings.ClassCancelledByClub") DomainEventHandler<ForeignEvent> classCancelled(BookingConsumers c) { return handler("ClassCancelledByClub", c); }
        @Bean("bookings.DogLevelChanged") DomainEventHandler<ForeignEvent> dogLevel(BookingConsumers c) { return handler("DogLevelChanged", c); }
        @Bean("bookings.BookingBlockChanged") DomainEventHandler<ForeignEvent> bookingBlock(BookingConsumers c) { return handler("BookingBlockChanged", c); }
        @Bean("bookings.UpfrontPaymentSucceeded") DomainEventHandler<ForeignEvent> paid(BookingConsumers c) { return handler("UpfrontPaymentSucceeded", c); }
        @Bean("bookings.UpfrontPaymentFailed") DomainEventHandler<ForeignEvent> failed(BookingConsumers c) { return handler("UpfrontPaymentFailed", c); }
        private DomainEventHandler<ForeignEvent> handler(String type, BookingConsumers consumers) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<ForeignEvent> eventClass() { return ForeignEvent.class; }
                public void handle(String id, ForeignEvent event) { consumers.handle(event); }
            };
        }
    }
}
