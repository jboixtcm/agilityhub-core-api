package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobItem;
import com.agilityhub.core.platform.application.jobs.JobRunRecorder;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentTimeoutsJob} (S15 P7, S08 R-08-18; T-08-35): a booking paid before its item runs,
 * or settled between the read and the lock, is not in scope and its checkout is left alone.
 */
class PaymentTimeoutsJobSurvivorsTest {
    static final Instant AT = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    final SingleClassChargePort charges = mock(SingleClassChargePort.class);
    final PaymentTimeoutsJob job = new PaymentTimeoutsJob(bookings, cancellations, charges);
    final JobContext context = new JobContext("club-a", ZoneId.of("Europe/Madrid"), AT, LocalDate.parse("2026-10-05"), false,
            mock(ClubConfig.class), mock(JobRunRecorder.class), "run-1");
    /** Planned past bookings.paymentPendingMinutes (30, CATALEG_PARAMETRES): booked 31 minutes before the run. */
    final JobItem item = new JobItem("Booking", "booking-1", "CANCEL", Map.of("bookingId", "booking-1", "minutesPending", 31L));

    @Test void T_08_35_aBookingPaidBeforeItsItemRunsIsNotInScope() {
        // UpfrontPaymentSucceeded settled it after the plan: ACTIVE with paidAt, checkout link cleared (BookingConfirmationService.java:176-181).
        when(bookings.findById("booking-1")).thenReturn(Optional.of(booking(BookingState.ACTIVE, AT.minusSeconds(30))));

        var effect = job.apply(context, item);

        assertThat(effect.action()).isEqualTo("NOT_IN_SCOPE");
        assertThat(effect.counters()).isEmpty();
        verifyNoInteractions(cancellations, charges);
    }

    @Test void T_08_35_aBookingSettledBetweenTheReadAndTheSeatLockIsNotInScopeAndKeepsItsCheckout() {
        when(bookings.findById("booking-1")).thenReturn(Optional.of(booking(BookingState.PAYMENT_PENDING, null)));
        // BookingCancellationService#cancelPaymentPending re-reads under the lock and finds it no longer PAYMENT_PENDING.
        when(cancellations.cancelPaymentPending("booking-1", false)).thenReturn(Optional.empty());

        var effect = job.apply(context, item);

        assertThat(effect.action()).isEqualTo("NOT_IN_SCOPE");
        assertThat(effect.counters()).isEmpty();
        verifyNoInteractions(charges);
    }

    static Booking booking(BookingState state, Instant paidAt) {
        var booked = AT.minusSeconds(31 * 60);
        var charge = new Booking.Charge(ChargeMode.PAY_TO_BOOK, new Money(1200L, "EUR"), null, "cs_1", null, paidAt,
                paidAt == null ? "https://checkout.example.test/cs_1" : null);
        return new Booking("booking-1", "club-a", "class-a", "dog-duna", "member-laura", state, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                charge, null,
                paidAt == null ? 1L : 2L, booked, "account-laura", paidAt == null ? booked : paidAt, paidAt == null ? "account-laura" : null);
    }
}
