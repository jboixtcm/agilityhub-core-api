package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.payments.application.CheckoutService;
import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingsAutoConfiguration}: without an application bean, the S06
 * `ClassBookingsPort`, the S14 `BookingActivity` and the S08 `SingleClassChargePort` are the real adapters over the
 * collaborators the context hands them (not the S06/S14 null objects, which these replace).
 */
class BookingsAutoConfigurationSurvivors3Test {
    final BookingsAutoConfiguration configuration = new BookingsAutoConfiguration();
    final BookingRepository bookings = mock(BookingRepository.class);

    @Test void E11_T06_theClassBookingsPortIsTheRealAdapterOverTheBookings() {
        var port = configuration.classBookings(bookings, mock(WaitlistEntryRepository.class), mock(BookingCancellationService.class));

        assertThat(port).isInstanceOf(ClassBookingsAdapter.class);
        assertThat(port.activeBookings("class-a")).isEmpty();
        verify(bookings).forClass("class-a", BookingRepository.LIVE);
    }

    @Test void E11_T06_theBookingActivityIsTheRealAdapterOverTheBookings() {
        var from = Instant.parse("2026-09-07T00:00:00Z"); var until = Instant.parse("2026-10-05T00:00:00Z");
        var activity = configuration.bookingActivity(bookings, mock(BookingMemberAccess.class));

        assertThat(activity).isInstanceOf(BookingActivityAdapter.class);
        try (var tenant = TenantContext.open("club-a")) {
            assertThat(activity.dogsWithBooking("club-a", from, until)).isEmpty();
        }
        verify(bookings).startingBetween(from, until, List.of(BookingState.ACTIVE, BookingState.CANCELLED_LATE));
    }

    @Test @SuppressWarnings("unchecked") void E11_T06_theSingleClassChargePortIsTheRealAdapterOverTheCheckouts() {
        var checkouts = mock(CheckoutService.class);
        var charges = configuration.singleClassCharges(mock(BookingMemberAccess.class), checkouts, (ObjectProvider<PaymentProvider>) mock(ObjectProvider.class),
                mock(BookingContext.class), mock(CensusClubSettings.class));

        assertThat(charges).isInstanceOf(SingleClassCharges.class);
        charges.abandon("cs_1");
        verify(checkouts).expire("cs_1");
    }
}
