package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.platform.application.jobs.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-17 P7 `payment-timeouts` (every minute, module SINGLE_CLASS): a PAYMENT_PENDING booking with
 * `bookedAt + bookings.paymentPendingMinutes ≤ now` is cancelled by the system with reason PAYMENT_TIMEOUT
 * ({@link BookingCancellationService#cancelPaymentPending}: CANCELLED, `SeatReleased`, N-40 from S08's handler). In
 * the same item transaction its checkout session expires and its line is cancelled ({@link SingleClassChargePort#abandon},
 * E5-T10), so a late provider success can no longer settle a cancelled booking.
 * Idempotent by state: two runs cancel once.
 */
@Component
public class PaymentTimeoutsJob implements Job {
    private final BookingRepository bookings; private final BookingCancellationService cancellations; private final SingleClassChargePort charges;
    public PaymentTimeoutsJob(BookingRepository bookings, BookingCancellationService cancellations, SingleClassChargePort charges) {
        this.bookings = bookings; this.cancellations = cancellations; this.charges = charges;
    }
    @Override public JobName name() { return JobName.PAYMENT_TIMEOUTS; }

    @Override public List<JobItem> plan(JobContext context) {
        var minutes = Duration.ofMinutes(context.parameter("bookings.paymentPendingMinutes", Integer.class));
        var now = context.scheduledFor();
        return bookings.pendingSince(now.minus(minutes)).stream().map(b -> new JobItem("Booking", b.id(), "CANCEL",
                Map.of("bookingId", b.id(), "minutesPending", Duration.between(b.bookedAt(), now).toMinutes()))).toList();
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        var booking = bookings.findById(item.entityId()).orElse(null);
        if (booking == null || booking.state() != BookingState.PAYMENT_PENDING) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        if (cancellations.cancelPaymentPending(booking.id(), false).isEmpty()) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        // After the cancellation: the UpfrontPaymentFailed of the expired session then finds the booking already CANCELLED.
        if (booking.charge() != null && booking.charge().checkoutSessionId() != null) { charges.abandon(booking.charge().checkoutSessionId()); }
        return new JobEffect("CANCEL", item.detail(), Map.of("cancelled", 1L));
    }
}
