package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E5-T13 (review E5-T10 #3): R-08-18 `openCheckout` rethrows the provider failure and keeps every later failure as suppressed. */
class BookingConfirmationServiceTest {
    private final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    private final SingleClassChargePort charges = mock(SingleClassChargePort.class);
    private final BookingConfirmationService service = new BookingConfirmationService(null, null, null, null, null, null, cancellations,
            null, null, charges, null, null, null, null, null, null);

    private static BookingConfirmationService.Confirmed pending() {
        var booking = mock(Booking.class);
        when(booking.id()).thenReturn("b1");
        var checkout = new SingleClassChargePort.Pending("session-1", "payment-1", "m1", "b1", new Money(1200L, "EUR"), "Classe", Instant.EPOCH);
        return new BookingConfirmationService.Confirmed(booking, null, checkout);
    }

    @Test void T_08_24_whenAbandonThrowsAfterTheProviderFailureTheOriginalIsRethrownWithTheAbandonSuppressed() {
        var provider = new IllegalStateException("provider unavailable (injected)");
        var abandon = new IllegalStateException("abandon failed (injected)");
        when(charges.open(any())).thenThrow(provider);
        when(cancellations.cancelPaymentPending("b1", true)).thenReturn(Optional.empty());
        doThrow(abandon).when(charges).abandon("session-1");

        assertThatThrownBy(() -> service.openCheckout(pending())).isSameAs(provider)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(abandon));
        verify(cancellations).cancelPaymentPending("b1", true);
        verify(charges).abandon("session-1");
    }

    @Test void T_08_24_whenTheCancellationAndTheAbandonBothThrowBothAreSuppressedInOrder() {
        var provider = new IllegalStateException("provider unavailable (injected)");
        var cancel = new IllegalStateException("cancel failed (injected)");
        var abandon = new IllegalStateException("abandon failed (injected)");
        when(charges.open(any())).thenThrow(provider);
        when(cancellations.cancelPaymentPending("b1", true)).thenThrow(cancel);
        doThrow(abandon).when(charges).abandon("session-1");

        assertThatThrownBy(() -> service.openCheckout(pending())).isSameAs(provider)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(cancel, abandon));
    }
}
