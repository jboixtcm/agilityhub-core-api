package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.application.PendingChargeService.BookingCharge;
import com.agilityhub.core.payments.application.PendingChargeService.ChargedBooking;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PendingChargeService} (S12 R-12-25, R-12-10; T-12-08): a billed charge never changes — a
 * late cancellation or a new mark of its booking answers the same live charge, with no new charge and no void.
 */
class PendingChargeServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    static final ChargedBooking BOOKING = new ChargedBooking("booking-1", "member-1", "dog-1", "Duna", LocalDate.of(2026, 10, 6));

    final PendingChargeRepository charges = mock(PendingChargeRepository.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final PendingChargeService service = new PendingChargeService(charges, mock(BillingCensusAccess.class), mock(BillingCatalogAccess.class),
            configs, mock(BillingTexts.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void stubs() {
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.BILLING, Module.SINGLE_CLASS), null, Map.of()));
        var billed = new PendingCharge("charge-1", CLUB, "member-1", "dog-1", "booking-1", "price-1", new Money(1500, "EUR"), "Classe 06/10 — Duna",
                NOW.minusSeconds(86400), "invoice-1", null);
        when(charges.forBooking("booking-1")).thenReturn(Optional.of(billed));
    }

    @Test void T_12_08_aLateCancellationOfABilledClassKeepsItsBilledCharge() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(service.cancellation(BOOKING, true)).contains(new BookingCharge("charge-1", true));
        }
        verify(charges, never()).insert(any());
        verify(charges, never()).voided(any(), any());
    }

    @Test void T_12_08_aSecondMarkOfABilledClassAddsNoDuplicate() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(service.attendance(BOOKING, "PRESENT", "PENDING")).contains(new BookingCharge("charge-1", true));
        }
        verify(charges, never()).insert(any());
        verify(charges, never()).voided(any(), any());
    }
}
