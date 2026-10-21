package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.domain.PackBalanceState;
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.payments.persistence.PackBalance;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link MemberPlanChangeService} (admin plan change; no S12/S13 test id covers it, hence the task's
 * prefix): the catalog validates the change first, the current month is a valid effective month, and a PACK → MONTHLY change
 * charges the entry fee and marks the latest pack's discount as used only once.
 */
class MemberPlanChangeServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingCatalogAccess catalog = mock(BillingCatalogAccess.class);
    final PackBalanceRepository packs = mock(PackBalanceRepository.class);
    final UpfrontPayments upfront = mock(UpfrontPayments.class);
    final ClubClock clock = mock(ClubClock.class);
    final com.agilityhub.core.platform.application.ClubConfigService configs = mock(com.agilityhub.core.platform.application.ClubConfigService.class);
    final com.agilityhub.core.platform.application.ClubConfig config = mock(com.agilityhub.core.platform.application.ClubConfig.class);
    final MemberPlanChangeService service = new MemberPlanChangeService(census, catalog, packs, upfront, clock, configs);

    @BeforeEach void stubs() {
        when(clock.today(CLUB)).thenReturn(TODAY);
        when(configs.get(CLUB)).thenReturn(config); when(config.get("billing.nextInvoiceDayOfMonth", Integer.class)).thenReturn(1);
        when(catalog.plan("plan-pack")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("plan-pack", "PACK-10", "PACK", null, 1, null, null)));
        when(catalog.plan("plan-monthly")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("plan-monthly", "MONTHLY-1", "MONTHLY", null, 1, null, null)));
    }

    @Test void E11_T06_aPlanOrPriceTheCatalogRefusesChangesNothing() {
        doThrow(new ApiException(ErrorCode.PLAN_NOT_AVAILABLE)).when(catalog).validateChange("plan-monthly", "price-retired", TODAY);
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> service.change("member-a", "plan-monthly", "price-retired", null, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PLAN_NOT_AVAILABLE));
        }
        verify(census, never()).lockPlan(anyString());
        verify(census, never()).changePlan(any(), any(), any(), any());
    }

    @Test void E11_T06_theCurrentMonthIsAValidEffectiveMonth() {
        when(census.lockPlan("member-a")).thenReturn("plan-monthly");
        try (var tenant = TenantContext.open(CLUB)) {
            service.change("member-a", "plan-monthly", "price-1", "2026-09", null);
        }
        // E11-T15 step 1: the member has no date (the census mock answers none), so R-12-06's default is written with the plan.
        verify(census).changePlan("member-a", "plan-monthly", "price-1", LocalDate.of(2026, 10, 1));
    }

    /** E11-T15 step 1 (R-12-01, R-12-06): the admin's date, a kept date, a pack plan without one, and a closed month refused. */
    @Test void T_12_01_R_12_06_aMemberWithoutADateGetsTheAdminsOrTheDefaultAndOneWithADateKeepsIt() {
        when(census.lockPlan("member-a")).thenReturn("plan-pack");
        try (var tenant = TenantContext.open(CLUB)) {
            service.change("member-a", "plan-monthly", "price-1", null, LocalDate.of(2026, 11, 1));
            verify(census).changePlan("member-a", "plan-monthly", "price-1", LocalDate.of(2026, 11, 1));
            when(census.member("member-a")).thenReturn(Optional.of(new BillingCensusAccess.BillingMember("member-a", 1, "Laura", "Serra", null, "ACTIVE",
                    "plan-pack", LocalDate.of(2026, 12, 1), null, null, null, null, "ca", NOW)));
            service.change("member-a", "plan-monthly", "price-2", null, LocalDate.of(2026, 11, 1));
            verify(census).changePlan("member-a", "plan-monthly", "price-2", null);
            when(census.lockPlan("member-a")).thenReturn("plan-monthly");
            when(census.member("member-a")).thenReturn(Optional.empty());
            service.change("member-a", "plan-pack", "price-3", null, null);
            verify(census).changePlan("member-a", "plan-pack", "price-3", null);
            assertThatThrownBy(() -> service.change("member-a", "plan-monthly", "price-4", null, LocalDate.of(2026, 8, 31)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(e.details()).containsEntry("fieldErrors", List.of(Map.of("field", "nextInvoiceDate", "code", "INVALID_VALUE")));
                    });
            service.change("member-a", "plan-monthly", "price-5", null, LocalDate.of(2026, 9, 1));
            verify(census).changePlan("member-a", "plan-monthly", "price-5", LocalDate.of(2026, 9, 1));
        }
        verify(census, never()).changePlan(eq("member-a"), any(), eq("price-4"), any());
    }

    @Test void E11_T06_packToMonthlyChargesTheDiscountedEntryAndMarksTheLatestPackAsUsed() {
        var older = pack("pack-1", "2026-01-10", Map.of("playoffPackId", "legacy-1"), 2L);
        var latest = pack("pack-2", "2026-06-01", Map.of("playoffPackId", "legacy-2"), 3L);
        when(census.lockPlan("member-a")).thenReturn("plan-pack");
        when(packs.of("member-a", null)).thenReturn(List.of(latest, older));
        when(catalog.changeQuote("plan-pack", "plan-monthly", 10, false)).thenReturn(new BillingCatalogAccess.ChangeQuote(eur(2000), "PACK_TO_MEMBER"));
        try (var tenant = TenantContext.open(CLUB)) {
            service.change("member-a", "plan-monthly", "price-1", null, null);
        }
        verify(upfront).create(eq("member-a"), anyString(), eq(List.of(new UpfrontPayments.Charge("ENTRY_FEE", null, eur(2000)))));
        var saved = ArgumentCaptor.forClass(PackBalance.class);
        verify(packs).save(saved.capture(), eq(3L));
        assertThat(saved.getValue().id()).isEqualTo("pack-2");
        assertThat(saved.getValue().version()).isEqualTo(4L);
        // The pack's other source ids stay; the discount is now used.
        assertThat(saved.getValue().sourceIds()).containsEntry("playoffPackId", "legacy-2").containsEntry("entryDiscountApplied", true);
    }

    @Test void E11_T06_aPackWhoseDiscountWasUsedPaysTheFullEntryAndIsNotMarkedAgain() {
        when(census.lockPlan("member-a")).thenReturn("plan-pack");
        when(packs.of("member-a", null)).thenReturn(List.of(pack("pack-2", "2026-06-01", Map.of("entryDiscountApplied", true), 3L)));
        when(catalog.changeQuote("plan-pack", "plan-monthly", 10, true)).thenReturn(new BillingCatalogAccess.ChangeQuote(eur(4000), null));
        try (var tenant = TenantContext.open(CLUB)) {
            service.change("member-a", "plan-monthly", "price-1", null, null);
        }
        verify(catalog).changeQuote("plan-pack", "plan-monthly", 10, true);
        verify(upfront).create(eq("member-a"), anyString(), eq(List.of(new UpfrontPayments.Charge("ENTRY_FEE", null, eur(4000)))));
        verify(packs, never()).save(any(), any());
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static Money eur(long amount) { return new Money(amount, "EUR"); }

    static PackBalance pack(String id, String openedOn, Map<String, Object> sourceIds, long version) {
        return new PackBalance(id, CLUB, "member-a", "dog-1", "plan-pack", "payment-" + id, 10, 4, 6, openedOn, "2026-12-31", PackBalanceState.ACTIVE,
                List.of(), null, null, null, sourceIds, version, NOW, null);
    }
}
