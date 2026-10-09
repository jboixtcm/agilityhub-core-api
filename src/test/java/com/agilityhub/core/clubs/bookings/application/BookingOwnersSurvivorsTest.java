package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.shared.application.BookingOwnerAccess;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingOwners} (S08 R-08-18, S12 PAY_TO_BOOK; T-08-24): only a CANCELLED booking is
 * cancelled, «cancelled before confirmation» needs a cancelled, unpaid PAY_TO_BOOK booking, and only a PAYMENT_PENDING
 * booking whose checkout is open offers it.
 */
class BookingOwnersSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");
    static final String URL = "https://checkout.example.test/c/cs_pending";

    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingOwners owners = new BookingOwners(bookings);

    @Test void T_08_18_onlyAnInTimeMemberCancellationIsCancelled() {
        stub(plain("booking-cancelled", BookingState.CANCELLED));
        stub(plain("booking-late", BookingState.CANCELLED_LATE));
        stub(plain("booking-active", BookingState.ACTIVE));

        assertThat(owners.cancelled("booking-cancelled")).isTrue();
        assertThat(owners.cancelled("booking-late")).isFalse();
        assertThat(owners.cancelled("booking-active")).isFalse();
        assertThat(owners.cancelled("booking-unknown")).isFalse();
    }

    @Test void T_08_24_cancelledBeforeConfirmationNeedsACancelledUnpaidPayToBookBooking() {
        // PAYMENT_TIMEOUT: the checkout link is gone (Booking.Charge#settled), never paid.
        stub(payToBook("booking-timeout", BookingState.CANCELLED, null, null));
        stub(payToBook("booking-pending", BookingState.PAYMENT_PENDING, null, URL));
        // Paid 5 minutes after booking (within bookings.paymentPendingMinutes = 30), then cancelled in time by Laura.
        stub(payToBook("booking-paid-cancelled", BookingState.CANCELLED, NOW.minusSeconds(86_400 - 300), null));
        stub(plain("booking-plain-cancelled", BookingState.CANCELLED));

        assertThat(owners.cancelledBeforeConfirmation("booking-timeout")).isTrue();
        assertThat(owners.cancelledBeforeConfirmation("booking-pending")).as("still waiting for the payment").isFalse();
        assertThat(owners.cancelledBeforeConfirmation("booking-paid-cancelled")).isFalse();
        assertThat(owners.cancelledBeforeConfirmation("booking-plain-cancelled")).as("no charge at all").isFalse();
    }

    @Test void T_08_24_aPendingPayToBookBookingOffersItsOpenCheckout() {
        stub(payToBook("booking-pending", BookingState.PAYMENT_PENDING, null, URL));
        // Created PAYMENT_PENDING with the session id; the URL is kept right after the commit (BookingConfirmationService#keepCheckoutUrl).
        stub(payToBook("booking-opening", BookingState.PAYMENT_PENDING, null, null));

        assertThat(owners.checkout("booking-pending")).contains(new BookingOwnerAccess.Checkout("cs_pending", URL));
        assertThat(owners.checkout("booking-opening")).isEmpty();
        assertThat(owners.checkout("booking-unknown")).isEmpty();
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    private void stub(Booking b) { when(bookings.findById(b.id())).thenReturn(Optional.of(b)); }

    private static Booking plain(String id, BookingState state) { return booking(id, state, null); }

    /** A PAY_TO_BOOK charge as BookingConfirmationService writes it (:85 session id, :176 `paidAt`; `paymentIntentId` is never set). */
    private static Booking payToBook(String id, BookingState state, Instant paidAt, String url) {
        // One checkout session per booking: booking-pending's is cs_pending.
        return booking(id, state, new Booking.Charge(ChargeMode.PAY_TO_BOOK, new Money(1200L, "EUR"), null, "cs_" + id.substring("booking-".length()), null,
                paidAt, url));
    }

    private static Booking booking(String id, BookingState state, Booking.Charge charge) {
        var booked = NOW.minusSeconds(86_400);
        boolean cancelled = state == BookingState.CANCELLED || state == BookingState.CANCELLED_LATE;
        boolean timeout = cancelled && charge != null && charge.paidAt() == null;
        // A PAYMENT_TIMEOUT happens bookings.paymentPendingMinutes (30) after booking.
        Instant cancelledAt = !cancelled ? null : state == BookingState.CANCELLED_LATE ? STARTS.minusSeconds(3600) : timeout ? booked.plusSeconds(1800) : NOW;
        Integer minutesBefore = cancelled ? (int) java.time.Duration.between(cancelledAt, STARTS).toMinutes() : null;
        // Two PAYMENT_PENDING bookings of one test are two dogs' (a dog has one live booking per class): booking-opening is Marc's Nala.
        boolean marc = id.equals("booking-opening");
        String account = marc ? "account-marc" : "account-laura", name = marc ? "Marc" : "Laura";
        return new Booking(id, "club-a", "class-a", marc ? "dog-nala" : "dog-duna", marc ? "member-marc" : "member-laura", state, BookingOrigin.APP, booked,
                new Booking.Actor(account, null, name), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                cancelledAt,
                !cancelled ? null : timeout ? new Booking.Canceller(null, ActorRole.SYSTEM, null, null) : new Booking.Canceller(account, ActorRole.MEMBER, name, null),
                !cancelled ? null : timeout ? BookingCancelReason.PAYMENT_TIMEOUT : BookingCancelReason.MEMBER, null,
                cancelled ? state == BookingState.CANCELLED_LATE : null, minutesBefore,
                null, null, null, null, null, charge, null,
                cancelled ? 2L : 1L, booked, account, cancelled ? cancelledAt : booked, timeout ? null : account);
    }
}
