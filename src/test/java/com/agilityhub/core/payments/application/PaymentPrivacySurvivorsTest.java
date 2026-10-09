package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.PaymentOperation;
import com.agilityhub.core.payments.persistence.PaymentOperationRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentPrivacy}: the queued "forget the provider customer" command completes only inside a
 * transaction whose claim still holds (the fence), with the real {@link PaymentRetryPolicy} over a mocked command repository.
 */
class PaymentPrivacySurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    final PaymentOperationRepository operations = mock(PaymentOperationRepository.class);
    final PaymentProviderRegistry provider = mock(PaymentProviderRegistry.class);
    final BillingTransactions tx = mock(BillingTransactions.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final PaymentPrivacy privacy = new PaymentPrivacy(operations, provider, tx, clock);

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        ReflectionTestUtils.setField(privacy, "retries", new PaymentRetryPolicy(configs, operations, tx, clock));
        when(operations.findById("op-1")).thenReturn(Optional.of(
                new PaymentOperation("op-1", CLUB, "FORGET", "cus_1", "cus_1", null, "forget:cus_1", null, null, null, NOW, null)));
        when(operations.claim("op-1", false)).thenReturn(new PaymentOperationRepository.Claim("token-1", false));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void E11_T06_aForgottenCustomerCompletesItsCommandUnderTheHeldClaim() {
        when(operations.fence("op-1", "token-1")).thenReturn(true);

        privacy.execute("op-1");

        verify(provider).forgetCustomer("cus_1");
        verify(operations).fence("op-1", "token-1");
        verify(operations).completed("op-1", "cus_1");
    }

    @Test void E11_T06_aClaimLostDuringTheProviderCallNeverCompletesTheCommand() {
        when(operations.fence("op-1", "token-1")).thenReturn(false);

        assertThatThrownBy(() -> privacy.execute("op-1")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));

        verify(operations, never()).completed(any(), any());
    }
}
