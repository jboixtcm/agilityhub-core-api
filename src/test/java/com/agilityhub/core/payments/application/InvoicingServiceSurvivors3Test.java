package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.InactivityFeePort;
import com.agilityhub.core.shared.application.LeaveBillingPort;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT session-10 survivors of {@link InvoicingService} (S12 R-12-05/06/09/25; T-12-03, T-12-05): the context reads the
 * club's own invoice day and cash period, and a pending charge's line carries the tax of its catalog price. Collaborators are
 * mocks (fixture copied from {@link InvoicingServiceSurvivorsTest}); fictional members only.
 */
class InvoicingServiceSurvivors3Test {
    static final String CLUB = "club-a";
    static final YearMonth PERIOD = YearMonth.of(2026, 9);
    static final LocalDate ISSUE = LocalDate.of(2026, 8, 25);
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");

    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingCatalogAccess catalog = mock(BillingCatalogAccess.class);
    final PendingChargeRepository charges = mock(PendingChargeRepository.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final BillingProviderSettings providers = mock(BillingProviderSettings.class);
    final ClubClock clock = mock(ClubClock.class);
    final InvoicingService service = new InvoicingService(census, catalog, mock(InactivityFeePort.class), mock(LeaveBillingPort.class), charges,
            mock(InvoiceRepository.class), configs, providers, mock(BillingTexts.class), clock);

    @BeforeEach void stubs() {
        when(clock.today(CLUB)).thenReturn(ISSUE);
        when(providers.enabledProviders()).thenReturn(Set.of("MANUAL"));
    }

    @Test void T_12_03_theContextReadsTheClubsOwnInvoiceDayAndCashPeriod() {
        when(configs.get(CLUB)).thenReturn(config(Map.of("billing.nextInvoiceDayOfMonth", 15, "billing.cashInvoicing", "SEMESTER",
                "billing.cashPeriodMonths", 3, "billing.taxIncluded", true), Set.of()));
        try (var tenant = TenantContext.open(CLUB)) {
            var settings = service.context().settings();
            assertThat(settings.nextInvoiceDayOfMonth()).isEqualTo(15);
            assertThat(settings.cashPeriodMonths()).isEqualTo(3);
        }
    }

    @Test void T_12_05_aPendingChargesLineCarriesTheTaxOfItsCatalogPrice() {
        when(configs.get(CLUB)).thenReturn(config(Map.of("billing.nextInvoiceDayOfMonth", 1, "billing.cashInvoicing", "MONTHLY",
                "billing.cashPeriodMonths", 6, "billing.taxIncluded", true), Set.of(Module.SINGLE_CLASS)));
        when(catalog.plan("plan-monthly")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("plan-monthly", "MONTHLY-1", "MONTHLY", null, 1, null, null)));
        when(catalog.price("price-single")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPrice("price-single", eur(1210), new BigDecimal("21"))));
        when(charges.unbilled(any())).thenReturn(List.of(new PendingCharge("charge-1", CLUB, "member-eva", "dog-1", "booking-1", "price-single", eur(1210),
                "Classe 07/09 - Duna", NOW, null, null)));
        // Not due for September itself (next date in October): only its pending single class is billed (R-12-25).
        var member = member("member-eva", PERIOD.plusMonths(1).atDay(1));
        try (var tenant = TenantContext.open(CLUB)) {
            var outcome = service.linesFor(member, PERIOD);
            assertThat(outcome).isInstanceOfSatisfying(InvoicingRules.Billed.class, billed ->
                    assertThat(billed.lines()).singleElement().satisfies(line -> {
                        assertThat(line.origin()).isEqualTo(InvoiceLineOrigin.SINGLE_CLASS);
                        // 12,10 € with 21 % included: 10,00 € + 2,10 € (R-12-09).
                        assertThat(line.amounts().taxPercent()).isEqualByComparingTo("21");
                        assertThat(line.amounts().base()).isEqualTo(eur(1000));
                        assertThat(line.amounts().tax()).isEqualTo(eur(210));
                        assertThat(line.amounts().total()).isEqualTo(eur(1210));
                    }));
        }
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static Money eur(long amount) { return new Money(amount, "EUR"); }

    static ClubConfig config(Map<String, Object> parameters, Set<Module> modules) {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        return new ClubConfig(club, parameters, modules, null, Map.of());
    }

    static BillingMember member(String id, LocalDate nextInvoiceDate) {
        var method = new BillingCensusAccess.PaymentMethod("MANUAL", false, null, "Eva Roca", null, null, null, false, "cash", null);
        return new BillingMember(id, 3, "Eva", "Roca", "Vidal", "ACTIVE", "plan-monthly", nextInvoiceDate, null, null, method, "00000000T", "ca", NOW);
    }
}
