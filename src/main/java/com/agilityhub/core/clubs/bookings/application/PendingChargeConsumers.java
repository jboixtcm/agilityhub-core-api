package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.payments.application.PendingChargeService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-25 consumers of S10 `AttendanceMarked{state, previousState}` and S08 `BookingCancelled{late}` (E8-T02): a booking of a
 * `CHARGE_ON_ATTENDANCE` single class gets its `PendingCharge` from `payments` ({@link PendingChargeService}: charge, void,
 * reinstate), and the booking's `charge.chargeInvoiceLineRef` provisionally names it. They live here because `payments`
 * cannot read bookings (S08 already depends on it). Idempotent: a redelivered event finds the same charge and the same stamp.
 * Bookings of another mode (PAY_TO_BOOK is E8-T04's) and clubs without `BILLING`/`SINGLE_CLASS` are left alone.
 */
@Service
public class PendingChargeConsumers {
    private final BookingRepository bookings; private final BookingMemberAccess census; private final PendingChargeService charges;
    private final ClubConfigService configs;
    public PendingChargeConsumers(BookingRepository bookings, BookingMemberAccess census, PendingChargeService charges, ClubConfigService configs) {
        this.bookings = bookings; this.census = census; this.charges = charges; this.configs = configs;
    }

    public void attendanceMarked(AttendanceEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var booking = chargeable(Objects.toString(event.payload().get("bookingId"), null));
            if (booking.isEmpty()) { return; }
            stamp(booking.get(), charges.attendance(view(booking.get()), string(event.payload().get("state")), string(event.payload().get("previousState"))));
        }
    }
    public void bookingCancelled(BookingEvent event) {
        if (!Boolean.TRUE.equals(event.payload().get("late"))) { return; }
        try (var tenant = TenantContext.open(event.clubId())) {
            var booking = chargeable(event.aggregateId());
            if (booking.isEmpty()) { return; }
            stamp(booking.get(), charges.cancellation(view(booking.get()), true));
        }
    }

    private Optional<Booking> chargeable(String bookingId) {
        if (bookingId == null) { return Optional.empty(); }
        return bookings.findById(bookingId).filter(booking -> booking.charge() != null && booking.charge().mode() == ChargeMode.CHARGE_ON_ATTENDANCE);
    }
    private PendingChargeService.ChargedBooking view(Booking booking) {
        var zone = ZoneId.of(configs.get(TenantContext.require()).club().timeZone());
        String dogName = census.dog(booking.dogId()).map(BookingMemberAccess.Dog::name).orElse(null);
        return new PendingChargeService.ChargedBooking(booking.id(), booking.memberId(), booking.dogId(), dogName,
                booking.classStartsAt().atZone(zone).toLocalDate());
    }
    private void stamp(Booking booking, Optional<String> chargeId) { chargeId.ifPresent(id -> bookings.stampChargeRef(booking.id(), id)); }
    private static String string(Object value) { return value == null ? null : value.toString(); }

    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean("payments.pendingCharges.AttendanceMarked") DomainEventHandler<AttendanceEvent> attendanceMarked(PendingChargeConsumers consumers) {
            return new DomainEventHandler<>() {
                public String eventType() { return "AttendanceMarked"; } public Class<AttendanceEvent> eventClass() { return AttendanceEvent.class; }
                public void handle(String id, AttendanceEvent event) { consumers.attendanceMarked(event); }
            };
        }
        @Bean("payments.pendingCharges.BookingCancelled") DomainEventHandler<BookingEvent> bookingCancelled(PendingChargeConsumers consumers) {
            return new DomainEventHandler<>() {
                public String eventType() { return "BookingCancelled"; } public Class<BookingEvent> eventClass() { return BookingEvent.class; }
                public void handle(String id, BookingEvent event) { consumers.bookingCancelled(event); }
            };
        }
    }
}
