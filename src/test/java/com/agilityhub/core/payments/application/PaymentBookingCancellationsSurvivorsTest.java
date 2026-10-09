package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivor of {@link PaymentBookingCancellations} (S12 R-12-20): the handler subscribes to `BookingCancelled`. */
class PaymentBookingCancellationsSurvivorsTest {
    @Test void T_12_17_theRefundHandlerSubscribesToBookingCancelled() {
        var handler = new PaymentBookingCancellations(mock(UpfrontPaymentRepository.class), mock(PaymentRefunds.class), mock(ClubConfigService.class));
        assertThat(handler.eventType()).isEqualTo("BookingCancelled");
        assertThat(handler.eventClass()).isEqualTo(PaymentBookingCancellations.Event.class);
    }
}
