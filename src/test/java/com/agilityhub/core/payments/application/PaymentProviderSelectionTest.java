package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.stripe.StripePaymentProvider;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentProviderSelectionTest {
    final ClubPaymentProviders clubs = mock(ClubPaymentProviders.class);
    final FakePaymentProvider fake = mock(FakePaymentProvider.class);
    final StripePaymentProvider stripe = mock(StripePaymentProvider.class);
    final ApplicationContextRunner runner = new ApplicationContextRunner().withPropertyValues("spring.profiles.active=local").withUserConfiguration(PaymentProviderRegistry.class)
            .withBean(java.time.Clock.class, java.time.Clock::systemUTC).withBean(ClubPaymentProviders.class, () -> clubs).withBean(FakePaymentProvider.class, () -> fake)
            .withBean(StripePaymentProvider.class, () -> stripe);
    @Test void T_12_15_theLocalSmokeCanExplicitlySelectTheRealStripeAdapter() {
        when(clubs.stripe("demo")).thenReturn(Optional.of(new StripeProviderSettings(true, null, null, null, "test", null)));
        try (var tenant = TenantContext.open("demo")) {
            runner.run(context -> assertThat(context.getBean(PaymentProviderRegistry.class).resolve()).isSameAs(fake));
            runner.withPropertyValues("core.payments.local-real-stripe=true")
                    .run(context -> assertThat(context.getBean(PaymentProviderRegistry.class).resolve()).isSameAs(stripe));
        }
    }
}
