package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.payments.application.PackBalanceService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentsPackBalanceAdapter} (S08 R-08-17; T-08-11): a confirmation consumes one class of
 * the pack in force on the class date and keeps the id of the CONSUME movement S12 returns (BookingConfirmationService
 * stores it on the booking); without a date the club's today is used (no production caller of that overload today).
 */
class PaymentsPackBalanceAdapterSurvivors2Test {
    final PackBalanceService packs = mock(PackBalanceService.class);
    final BookingContext context = mock(BookingContext.class);
    final PaymentsPackBalanceAdapter adapter = new PaymentsPackBalanceAdapter(packs, context);

    @Test void T_08_11_aConfirmationConsumesThePackOfTheClassDateAndReturnsItsMovement() {
        // Wednesday 7 October's class, booked on Monday 5.
        when(packs.consume("member-laura", "dog-duna", "booking-1", LocalDate.parse("2026-10-07"))).thenReturn("movement-1");

        assertThat(adapter.consume("member-laura", "dog-duna", "booking-1", LocalDate.parse("2026-10-07"))).isEqualTo("movement-1");
    }

    @Test void T_08_11_aConsumptionWithoutADateUsesTheClubsToday() {
        when(context.today()).thenReturn(LocalDate.parse("2026-10-05"));
        when(packs.consume("member-laura", "dog-duna", "booking-1", LocalDate.parse("2026-10-05"))).thenReturn("movement-1");

        assertThat(adapter.consume("member-laura", "dog-duna", "booking-1")).isEqualTo("movement-1");
    }
}
