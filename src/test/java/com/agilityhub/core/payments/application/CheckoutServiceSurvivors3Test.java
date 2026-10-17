package com.agilityhub.core.payments.application;

import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.SignupCheckoutSession;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT session-10 survivors of {@link CheckoutService} (S04 R-04-26/27; T-04-22): the provider's expiry of a checkout
 * takes the census lock before it reads and closes the session, as every other write of the checkout does. Collaborators are
 * mocks (fixture copied from {@link CheckoutServiceSurvivorsTest}); fictional data.
 */
class CheckoutServiceSurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");

    final SignupPaymentAccess members = mock(SignupPaymentAccess.class);
    final UpfrontPayments payments = mock(UpfrontPayments.class);
    final SignupCheckoutRepository sessions = mock(SignupCheckoutRepository.class);
    @SuppressWarnings("unchecked") final ObjectProvider<PaymentProvider> gateways = mock(ObjectProvider.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    CheckoutService checkout;

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        checkout = new CheckoutService(members, payments, mock(CensusClubSettings.class), mock(ClubConfigService.class), sessions, gateways, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(IcuMessageSource.class), mock(PlatformTransactionManager.class));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void T_04_22_anExpiryLocksTheCensusBeforeItReadsAndClosesTheSession() {
        when(sessions.findById("session-1")).thenReturn(Optional.of(new SignupCheckoutSession("session-1", CLUB, "member-1", "PENDING", "payment",
                List.of("payment-1"), NOW.plusSeconds(3600), null)));
        when(sessions.finish("session-1", "EXPIRED", null)).thenReturn(true);

        checkout.expire("session-1");

        var order = inOrder(members, sessions, payments);
        order.verify(members).lock();
        order.verify(sessions).findById("session-1");
        order.verify(sessions).finish("session-1", "EXPIRED", null);
        order.verify(payments).checkout("member-1", "session-1", false);
    }
}
