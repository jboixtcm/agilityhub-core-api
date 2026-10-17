package com.agilityhub.core.payments.api;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT session-10 survivors of {@link CheckoutContracts.CheckoutSessionRequest} (S12 §6, T-12-16): a request is an S12
 * extension of the signup checkout only when it carries a booking or upfront payments. Fictional ids only.
 */
class CheckoutContractsSurvivors3Test {
    static final String SUCCESS = "https://club-a.example.test/ok";
    static final String CANCEL = "https://club-a.example.test/cancel";

    @Test void T_12_16_aCheckoutRequestIsExtendedOnlyByABookingOrUpfrontPayments() {
        assertThat(new CheckoutContracts.CheckoutSessionRequest("member-1", "token-1", SUCCESS, CANCEL).extended()).isFalse();
        assertThat(new CheckoutContracts.CheckoutSessionRequest("member-1", null, SUCCESS, CANCEL, "booking-1", null).extended()).isTrue();
        assertThat(new CheckoutContracts.CheckoutSessionRequest("member-1", null, SUCCESS, CANCEL, null, List.of("payment-1")).extended()).isTrue();
        assertThat(new CheckoutContracts.CheckoutSessionRequest("member-1", null, SUCCESS, CANCEL, "booking-1", List.of("payment-1")).extended()).isTrue();
    }
}
