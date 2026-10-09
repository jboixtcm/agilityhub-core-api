package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.Invoice;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link InvoicingService} (S12 R-12-01/08/09/19/25; T-12-01, T-12-04, T-12-05, T-12-18, T-12-22): the
 * club's `taxIncluded` and modules, an inactive member's month is its S13 fee, the numbering order (case-insensitive names,
 * then the member number), the month's pending charges, and a waiting receipt's member name.
 */
class InvoicingServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final YearMonth PERIOD = YearMonth.of(2026, 9);
    static final LocalDate ISSUE = LocalDate.of(2026, 8, 25);
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");
    static final Locale CA = Locale.forLanguageTag("ca");

    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingCatalogAccess catalog = mock(BillingCatalogAccess.class);
    final InactivityFeePort inactivity = mock(InactivityFeePort.class);
    final PendingChargeRepository charges = mock(PendingChargeRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final BillingProviderSettings providers = mock(BillingProviderSettings.class);
    final ClubClock clock = mock(ClubClock.class);
    final InvoicingService service = new InvoicingService(census, catalog, inactivity, mock(LeaveBillingPort.class), charges, invoices, configs, providers,
            mock(BillingTexts.class), clock);

    @BeforeEach void stubs() {
        when(clock.today(CLUB)).thenReturn(ISSUE);
        when(providers.enabledProviders()).thenReturn(Set.of("MANUAL"));
        when(configs.get(CLUB)).thenReturn(config(true, Set.of(Module.INACTIVITY)));
    }

    @Test void T_12_05_pricesIncludeTaxUnlessTheClubTurnsItOff() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(service.context().settings().taxIncluded()).isTrue();
            when(configs.get(CLUB)).thenReturn(config(false, Set.of(Module.INACTIVITY)));
            assertThat(service.context().settings().taxIncluded()).isFalse();
        }
    }

    @Test void T_12_22_aContextHasAModuleOnlyWhenTheClubHasItOn() {
        var context = new InvoicingService.Context(config(true, Set.of(Module.INACTIVITY)), null, ISSUE, CA);
        assertThat(context.module(Module.INACTIVITY)).isTrue();
        assertThat(context.module(Module.FAMILY_GROUP)).isFalse();
    }

    @Test void T_12_01_anInactiveMembersMonthIsItsInactivityFee() {
        when(catalog.plan("plan-monthly")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("plan-monthly", "MONTHLY-1", "MONTHLY", null, 1, null, null)));
        // S13 R-13-08: Eva owes 10,00 € for September; the plan has no current price, so only the fee can bill her.
        when(inactivity.feeFor("member-eva", PERIOD)).thenReturn(Optional.of(eur(1000)));
        try (var tenant = TenantContext.open(CLUB)) {
            var outcome = service.linesFor(member("member-eva", 3, "Eva", "Roca", "Vidal"), PERIOD);
            assertThat(outcome).isInstanceOfSatisfying(InvoicingRules.Billed.class, billed -> {
                assertThat(billed.lines()).extracting(InvoicingRules.Line::origin).containsExactly(InvoiceLineOrigin.INACTIVITY_FEE);
                assertThat(billed.total("EUR")).isEqualTo(eur(1000));
            });
        }
    }

    @Test void T_12_04_theMonthListsItsMembersInTheNumberingOrderWithTheirPendingCharges() {
        when(census.activeMembers()).thenReturn(List.of(member("member-bosch", 1, "Anna", "Bosch", "Puig"), member("member-abad", 2, "Zoe", "Abad", "Puig")));
        var charge = new PendingCharge("charge-1", CLUB, "member-abad", "dog-1", "booking-1", "price-single", eur(1200), "Classe 07/09 — Duna", NOW, null, null);
        when(charges.unbilled(any())).thenReturn(List.of(charge));
        try (var tenant = TenantContext.open(CLUB)) {
            var plan = service.plan(PERIOD);
            assertThat(plan.members().keySet()).containsExactly("member-abad", "member-bosch");
            assertThat(plan.charges()).containsOnlyKeys("charge-1").containsEntry("charge-1", charge);
        }
    }

    @Test void T_12_18_aWaitingReceiptShowsItsMembersCurrentNameElseTheFrozenOne() {
        when(invoices.forNextRun()).thenReturn(List.of(receipt("receipt-1", "member-eva", "Eva Roca"), receipt("receipt-2", "member-gone", "Pau Serra")));
        when(census.member("member-eva")).thenReturn(Optional.of(member("member-eva", 3, "Eva", "Roca", "Vidal")));
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(service.plan(PERIOD).waiting()).extracting(InvoicingService.WaitingReceipt::memberName).containsExactly("Eva Roca Vidal", "Pau Serra");
        }
    }

    @Test void T_12_04_namesCompareWithoutCaseAndAMissingOneIsBlank() {
        var order = InvoicingService.order(CA);
        // Case is no difference (Collator.SECONDARY): the last names are equal, so the first name decides, whichever case sorts first.
        assertThat(order.compare(member("member-1", 1, "Bernat", "garcia", "Puig"), member("member-2", 2, "Anna", "Garcia", "Puig"))).isPositive();
        assertThat(order.compare(member("member-3", 3, "Bernat", "Garcia", "Puig"), member("member-4", 4, "Anna", "garcia", "Puig"))).isPositive();
        // The last name decides before the first name and the number.
        assertThat(order.compare(member("member-5", 9, "Zoe", "Abad", "Puig"), member("member-6", 1, "Anna", "Bosch", "Puig"))).isNegative();
        assertThat(order.compare(member("member-6", 1, "Anna", "Bosch", "Puig"), member("member-5", 9, "Zoe", "Abad", "Puig"))).isPositive();
        // A missing second last name is blank: before any other.
        assertThat(order.compare(member("member-7", 9, "Zoe", "Abad", null), member("member-8", 1, "Anna", "Abad", "Puig"))).isNegative();
    }

    @Test void T_12_04_equalNamesGoByMemberNumberAndAMissingNumberLast() {
        var order = InvoicingService.order(CA);
        var second = member("member-a", 2, "Laura", "Serra", "Puig");
        var first = member("member-b", 1, "Laura", "Serra", "Puig");
        assertThat(order.compare(first, second)).isNegative();
        assertThat(order.compare(second, member("member-0", null, "Laura", "Serra", "Puig"))).isNegative();
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static Money eur(long amount) { return new Money(amount, "EUR"); }

    static ClubConfig config(boolean taxIncluded, Set<Module> modules) {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        return new ClubConfig(club, Map.of("billing.nextInvoiceDayOfMonth", 1, "billing.cashInvoicing", "MONTHLY", "billing.cashPeriodMonths", 6,
                "billing.taxIncluded", taxIncluded), modules, null, Map.of());
    }

    static BillingMember member(String id, Integer number, String firstName, String lastName1, String lastName2) {
        var method = new BillingCensusAccess.PaymentMethod("MANUAL", false, null, firstName + " " + lastName1, null, null, null, false, "cash", null);
        return new BillingMember(id, number, firstName, lastName1, lastName2, "ACTIVE", "plan-monthly", PERIOD.atDay(1), null, null, method, "00000000T", "ca", NOW);
    }

    /** A manual SEPA_DD receipt waiting for the next remittance (R-12-19), with the member's name frozen at issue. */
    static Invoice receipt(String id, String memberId, String frozenName) {
        var total = eur(2500);
        return new Invoice(id, CLUB, "2026", 900, "2026-0900", "2026-08-01", "2026-08", memberId, new Invoice.MemberSnapshot(2, frozenName, null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.ADJUSTMENT, null, null, "Ajust", total, BigDecimal.ZERO, eur(0), total)),
                total, eur(0), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, null, null, "MANDATE-" + memberId, null, null),
                InvoiceStatus.PENDING, InvoiceKind.MANUAL, null, null, true, null, null, null, null, null, null, eur(0), null, 0L, NOW, null, NOW, null);
    }
}
