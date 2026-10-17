package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.payments.application.CheckoutService;
import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.platform.application.CensusClubSettings;
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
 * E11-T06 PIT survivors of {@link SingleClassCharges} (S08 R-08-18; T-08-24): opening a PAY_TO_BOOK checkout for an owner
 * the census no longer returns is `NOT_FOUND`, and the payment provider is never asked for a session.
 */
class SingleClassChargesSurvivors3Test {
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final CheckoutService checkouts = mock(CheckoutService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<PaymentProvider> gateways = mock(ObjectProvider.class);
    final CensusClubSettings clubs = mock(CensusClubSettings.class);
    final SingleClassCharges charges = new SingleClassCharges(census, checkouts, gateways, mock(BookingContext.class), clubs);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() { tenant = TenantContext.open("club-a"); }
    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_08_24_openingTheCheckoutOfAnOwnerTheCensusDoesNotReturnIsNotFoundWithoutCallingTheProvider() {
        when(clubs.appHost()).thenReturn("app.example.test");
        when(census.member("member-laura")).thenReturn(Optional.empty());
        var pending = new SingleClassChargePort.Pending("checkout-1", "upfront-1", "member-laura", "booking-1", new Money(1200, "EUR"),
                "Classe solta", Instant.parse("2026-10-05T08:15:00Z"));

        assertThatThrownBy(() -> charges.open(pending))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(gateways, never()).getObject();
        verifyNoInteractions(checkouts);
    }
}
