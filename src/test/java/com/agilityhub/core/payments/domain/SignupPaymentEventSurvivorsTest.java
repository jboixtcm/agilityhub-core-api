package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E11-T06 PIT survivors of {@link SignupPaymentEvent}: the outbox records which aggregate the event is about. */
class SignupPaymentEventSurvivorsTest {

    @Test void E11_T06_upfrontPaymentEventsAreAboutTheUpfrontPaymentAggregate() {
        var event = new SignupPaymentEvent("UpfrontPaymentSucceeded", "club-a", "payment-1", Instant.parse("2026-09-24T08:00:00Z"), Map.of(),
                null, null, DomainEvent.Origin.WEBHOOK);
        assertThat(event.aggregateType()).isEqualTo("UpfrontPayment");
    }
}
