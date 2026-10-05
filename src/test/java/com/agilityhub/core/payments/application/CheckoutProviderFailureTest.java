package com.agilityhub.core.payments.application;

import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.SignupCheckoutSession;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class CheckoutProviderFailureTest {
    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"PAYMENT_PROVIDER_NOT_ENABLED", "PROVIDER_CONFIG_INVALID", "RATE_LIMITED"})
    void T_04_23_T_12_16_rejectionKeepsLocalExpiryWhenProviderExpiryFails(ErrorCode code) {
        var sessions = mock(SignupCheckoutRepository.class);
        var payments = mock(UpfrontPayments.class);
        var provider = mock(PaymentProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PaymentProvider> gateways = mock(ObjectProvider.class);
        when(gateways.getIfAvailable()).thenReturn(provider);
        when(sessions.openSignup("member")).thenReturn(List.of(new SignupCheckoutSession("session", "club", "member",
                "PENDING", "payment", List.of("payment"), Instant.EPOCH, null)));
        when(sessions.finish("session", "EXPIRED", null)).thenReturn(true);
        doThrow(new ApiException(code)).when(provider).expire("session");
        var checkout = new CheckoutService(mock(SignupPaymentAccess.class), payments, mock(CensusClubSettings.class),
                mock(ClubConfigService.class), sessions, gateways, mock(IdentityTransactions.class), Clock.systemUTC(),
                mock(IcuMessageSource.class), mock(PlatformTransactionManager.class));

        assertThatCode(() -> checkout.rejected("member", List.of("payment"), true)).doesNotThrowAnyException();
        verify(sessions).finish("session", "EXPIRED", null);
        verify(payments).checkout("member", "session", false);
        verify(provider).expire("session");
    }
}
