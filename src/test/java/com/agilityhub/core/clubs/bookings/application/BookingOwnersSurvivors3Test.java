package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingOwners#ownerOf} (S08 R-08-18, S12 `POST /checkout-sessions` with a
 * `bookingId`; T-08-24): the owner of a booking of the open tenant is its `Booking.memberId`, which
 * `BillingContractAccess#checkoutReferences` compares with the paying member; an unknown booking has none (404 there).
 */
class BookingOwnersSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingOwners owners = new BookingOwners(bookings);

    @Test void T_08_24_aBookingsOwnerIsTheMemberItWasBookedFor() {
        var booked = NOW.minusSeconds(600);
        // Laura's PAY_TO_BOOK booking of Duna, still waiting for its payment.
        var pending = new Booking("booking-1", "club-a", "class-a", "dog-duna", "member-laura", BookingState.PAYMENT_PENDING, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                new Booking.Charge(ChargeMode.PAY_TO_BOOK, new Money(1200L, "EUR"), null, "cs_1", null, null, "https://checkout.example.test/cs_1"), null,
                1L, booked, "account-laura", booked, "account-laura");
        when(bookings.findById("booking-1")).thenReturn(Optional.of(pending));

        assertThat(owners.ownerOf("booking-1")).contains("member-laura");
        assertThat(owners.ownerOf("booking-unknown")).isEmpty();
    }
}
