package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.persistence.IdempotencyRepository;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E5-T31 step 3 (CONVENCIONS_API §7, ruling E80; review E5-T30 #4): every call to the payment provider times out well within the
 * Idempotency-Key's claim lease, so a keyed checkout never outlives its claim unless its process stopped. CONVENCIONS_API §7
 * has no test id, so the tests carry the task's name (E5-T31's review nit, done in E8-T06), as `IdempotencyIT`'s do.
 */
class PaymentProviderContractTest {
    /** A provider whose client times out after {@code timeout}; it is never called. */
    record Provider(Duration callTimeout) implements PaymentProvider {
        public String createCheckoutSession(Request request) { throw new UnsupportedOperationException(); }
        public void complete(String sessionId) { throw new UnsupportedOperationException(); }
        public void expire(String sessionId) { throw new UnsupportedOperationException(); }
    }
    static PaymentProvider provider(Duration timeout) { return new Provider(timeout); }

    @Test void E5_T31_theFakeProvidersCallTimeoutStaysWellWithinTheIdempotencyClaimLease() {
        var fake = new FakeCheckoutGateway(null);
        assertThat(fake.callTimeout()).isPositive().isLessThanOrEqualTo(PaymentProvider.MAX_CALL_TIMEOUT);
        assertThat(PaymentProvider.requireTimeout(fake)).isSameAs(fake);
        // One keyed checkout makes at most two provider calls (the session, then its expiry after a failure): together at most a
        // tenth of the lease, which leaves the rest to the request's Mongo transactions.
        assertThat(PaymentProvider.MAX_CALL_TIMEOUT.multipliedBy(2)).isLessThanOrEqualTo(IdempotencyRepository.CLAIM_LEASE.dividedBy(10));
    }

    @Test void E5_T31_aProviderWhoseCallsCouldOutliveTheClaimNeverStarts() {
        for (var wrong : Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(-1), PaymentProvider.MAX_CALL_TIMEOUT.plusMillis(1), IdempotencyRepository.CLAIM_LEASE)) {
            assertThatThrownBy(() -> PaymentProvider.requireTimeout(provider(wrong))).as("timeout %s", wrong)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("call timeout " + wrong);
        }
        @SuppressWarnings("unchecked") ObjectProvider<PaymentProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(provider(PaymentProvider.MAX_CALL_TIMEOUT), provider(IdempotencyRepository.CLAIM_LEASE)));
        assertThatThrownBy(() -> new PaymentProviderTimeouts(providers).afterSingletonsInstantiated()).isInstanceOf(IllegalStateException.class);
        when(providers.orderedStream()).thenReturn(Stream.of(provider(PaymentProvider.MAX_CALL_TIMEOUT)));
        assertThatCode(() -> new PaymentProviderTimeouts(providers).afterSingletonsInstantiated()).doesNotThrowAnyException();
        when(providers.orderedStream()).thenReturn(Stream.empty());
        assertThatCode(() -> new PaymentProviderTimeouts(providers).afterSingletonsInstantiated()).as("no provider").doesNotThrowAnyException();
    }
}
