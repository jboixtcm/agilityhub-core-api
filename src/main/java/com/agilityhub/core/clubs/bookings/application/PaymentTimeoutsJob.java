package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.payments.application.CheckoutService;
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
 * <p>
 * E5-T30 round 2 (S04 R-04-26, ruling E79): P7 also expires every open signup checkout past its `expiresAt` whose expiry never
 * reached us (a lost callback, or no provider session at all: the process stopped after the checkout was prepared), with the
 * provider switched off too: `EXPIRE_CHECKOUT {checkoutSessionId, minutesExpired}` (`WOULD_EXPIRE_CHECKOUT` in a dry run), and
 * its rows are payable again ({@link CheckoutService#expireLapsed}). A club without SINGLE_CLASS has no P7; there the next write
 * that needs the rows releases them ({@link CheckoutService#releaseLapsed}).
 * Idempotent by state: two runs cancel once.
 */
@Component
public class PaymentTimeoutsJob implements Job {
    private final BookingRepository bookings; private final BookingCancellationService cancellations; private final SingleClassChargePort charges;
    private final CheckoutService checkouts;
    public PaymentTimeoutsJob(BookingRepository bookings, BookingCancellationService cancellations, SingleClassChargePort charges, CheckoutService checkouts) {
        this.bookings = bookings; this.cancellations = cancellations; this.charges = charges; this.checkouts = checkouts;
    }
    @Override public JobName name() { return JobName.PAYMENT_TIMEOUTS; }

    @Override public List<JobItem> plan(JobContext context) {
        var minutes = Duration.ofMinutes(context.parameter("bookings.paymentPendingMinutes", Integer.class));
        var now = context.scheduledFor();
        var items = new ArrayList<JobItem>(bookings.pendingSince(now.minus(minutes)).stream().map(b -> new JobItem("Booking", b.id(), "CANCEL",
                Map.of("bookingId", b.id(), "minutesPending", Duration.between(b.bookedAt(), now).toMinutes()))).toList());
        for (var lapsed : checkouts.lapsedSignupCheckouts(now)) {
            items.add(new JobItem(CHECKOUT, lapsed.sessionId(), "EXPIRE_CHECKOUT", Map.of("checkoutSessionId", lapsed.sessionId(),
                    "minutesExpired", Duration.between(lapsed.expiresAt(), now).toMinutes())));
        }
        return List.copyOf(items);
    }
    private static final String CHECKOUT = "CheckoutSession";

    @Override public JobEffect apply(JobContext context, JobItem item) {
        if (CHECKOUT.equals(item.entityType())) {
            return checkouts.expireLapsed(item.entityId(), context.scheduledFor())
                    ? new JobEffect("EXPIRE_CHECKOUT", item.detail(), Map.of("expiredCheckouts", 1L)) : new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of());
        }
        var booking = bookings.findById(item.entityId()).orElse(null);
        if (booking == null || booking.state() != BookingState.PAYMENT_PENDING) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        if (cancellations.cancelPaymentPending(booking.id(), false).isEmpty()) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        // After the cancellation: the UpfrontPaymentFailed of the expired session then finds the booking already CANCELLED.
        if (booking.charge() != null && booking.charge().checkoutSessionId() != null) { charges.abandon(booking.charge().checkoutSessionId()); }
        return new JobEffect("CANCEL", item.detail(), Map.of("cancelled", 1L));
    }
}
