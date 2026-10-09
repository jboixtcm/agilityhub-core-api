package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.stripe.StripePaymentProvider;
import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.platform.application.StripeProviderSettings;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentProviderRegistry} (S12 R-12-13/20/21): with the club's Stripe enabled every call reaches
 * the resolved adapter and answers what it answered; with it disabled the stand-in supports nothing, refuses with
 * `PAYMENT_PROVIDER_NOT_ENABLED` and still declares the contract's timeout. No fake bean: the registry resolves Stripe.
 */
class PaymentProviderRegistrySurvivorsTest {
    static final String CLUB = "club-a";

    final ClubPaymentProviders clubs = mock(ClubPaymentProviders.class);
    @SuppressWarnings("unchecked") final ObjectProvider<FakePaymentProvider> fake = mock(ObjectProvider.class);
    final StripePaymentProvider stripe = mock(StripePaymentProvider.class);
    final PaymentProviderRegistry registry = new PaymentProviderRegistry(clubs, fake, stripe);

    @BeforeEach void setUp() { TenantContext.open(CLUB); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    void stripeEnabled(boolean enabled) {
        when(clubs.stripe(CLUB)).thenReturn(Optional.of(new StripeProviderSettings(enabled, null, null, null, "test", null)));
    }

    @Test void T_12_17_aRefundAnswersTheAdaptersResult() {
        stripeEnabled(true);
        var amount = new Money(4500, "EUR");
        var result = new PaymentProvider.RefundResult("re_1", "succeeded");
        when(stripe.refund("ch_1", amount, "refund-key-1", "requested_by_customer")).thenReturn(result);

        assertThat(registry.refund("ch_1", amount, "refund-key-1", "requested_by_customer")).isSameAs(result);
    }

    @Test void T_12_15_aWebhookIsParsedByTheAdapter() {
        stripeEnabled(true);
        var event = new PaymentProvider.WebhookEvent("evt_1", "payment_intent.succeeded", Instant.parse("2026-09-01T08:00:00Z"), null);
        when(stripe.parseWebhook("{}", "t=1,v1=abc", "whsec_test")).thenReturn(event);

        assertThat(registry.parseWebhook("{}", "t=1,v1=abc", "whsec_test")).isSameAs(event);
    }

    @Test void T_12_16_aCompletedSessionIsHandedToTheAdapter() {
        stripeEnabled(true);

        registry.complete("cs_1");

        verify(stripe).complete("cs_1");
    }

    @Test void T_12_15_theRegistryDeclaresTheContractsCallTimeout() {
        assertThat(registry.callTimeout()).isEqualTo(PaymentProvider.MAX_CALL_TIMEOUT);
    }

    @Test void T_12_15_aDisabledProviderSupportsNothingAndRefusesWithItsCode() {
        stripeEnabled(false);
        var disabled = registry.resolve();

        assertThat(registry.supports(PaymentProvider.Capability.CHECKOUT)).isFalse();
        assertThat(disabled.callTimeout()).isEqualTo(PaymentProvider.MAX_CALL_TIMEOUT);
        assertThatThrownBy(() -> disabled.createCheckoutSession(null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
        assertThatThrownBy(() -> registry.require(PaymentProvider.Capability.REFUND)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
        verifyNoInteractions(stripe);
    }
}
