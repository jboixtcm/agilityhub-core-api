package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of the {@link PaymentProvider} port's defaults (S12 R-12-13/20): an adapter supports nothing it does not
 * declare, refuses an undeclared call with a not-submitted `PAYMENT_PROVIDER_NOT_ENABLED`, and the keyed refund with an operation
 * id answers what the plain refund answers.
 */
class PaymentProviderSurvivorsTest {
    /** Only the abstract methods and the four-argument refund. */
    static final class MinimalProvider implements PaymentProvider {
        final RefundResult answer = new RefundResult("re_1", "succeeded");
        @Override public String createCheckoutSession(Request request) { return "https://checkout.example.test/cs_1"; }
        @Override public void complete(String sessionId) { }
        @Override public void expire(String sessionId) { }
        @Override public Duration callTimeout() { return MAX_CALL_TIMEOUT; }
        @Override public RefundResult refund(String chargeId, Money amount, String idempotencyKey, String reason) { return answer; }
    }

    final MinimalProvider provider = new MinimalProvider();

    @Test void T_12_15_aProviderSupportsNoCapabilityItDoesNotDeclare() {
        assertThat(provider.supports(PaymentProvider.Capability.REFUND)).isFalse();
    }

    @Test void T_12_17_theRefundWithAnOperationIdAnswersThePlainRefund() {
        assertThat(provider.refund("ch_1", new Money(4500, "EUR"), "refund-key-1", "requested_by_customer", "op-1")).isSameAs(provider.answer);
    }

    @Test void T_12_15_anUndeclaredOffSessionPaymentIsRefusedAsNotSubmitted() {
        assertThatThrownBy(() -> provider.createOffSessionPayment(null)).isInstanceOfSatisfying(PaymentNotSubmitted.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
    }
}
