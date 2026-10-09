package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.PackBalanceState;
import com.agilityhub.core.payments.domain.PackMovementType;
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.payments.persistence.PackBalance;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PackBalanceService} (S12 R-12-23/24, S18 R-18-10, S15 R-15-15a): migrated balances, the
 * payment-opened pack, the zero boundaries, the event payloads, the version increments and the guards that answer null or
 * false. The repository is an in-memory mock; fictional members only.
 */
class PackBalanceServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");
    static final LocalDate OPENED = LocalDate.of(2026, 6, 12);
    static final LocalDate CLASS_DATE = LocalDate.of(2026, 7, 1);

    final PackBalanceRepository repository = mock(PackBalanceRepository.class);
    final BillingCatalogAccess catalog = mock(BillingCatalogAccess.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final BillingEvents events = mock(BillingEvents.class);
    final ClubClock local = mock(ClubClock.class);
    final PackBalanceService service = new PackBalanceService(repository, catalog, census, configs, events, Clock.fixed(NOW, ZoneOffset.UTC), local);
    final Map<String, PackBalance> stored = new LinkedHashMap<>();
    TenantContext.Scope scope;

    @BeforeEach void setUp() {
        scope = TenantContext.open("club");
        packs(true);
        when(local.today("club")).thenReturn(OPENED);
        when(catalog.pack("plan")).thenReturn(new BillingCatalogAccess.PackTerms("plan", 10, 5));
        when(census.member("member")).thenReturn(Optional.of(new BillingCensusAccess.BillingMember("member", 7, "Laura", "Serra", null, "ACTIVE",
                "member-plan", null, null, null, null, null, "ca", null)));
        when(catalog.plan("member-plan")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("member-plan", "P", "PACK", null, 1, null, null)));
        when(repository.insert(any())).thenAnswer(call -> { var p = withVersion(call.getArgument(0), 0L); stored.put(p.id(), p); return p; });
        when(repository.save(any(), any())).thenAnswer(call -> { PackBalance p = call.getArgument(0); stored.put(p.id(), p); return p; });
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(repository.of(any(), any())).thenAnswer(call -> stored.values().stream()
                .filter(p -> p.memberId().equals(call.getArgument(0)) && p.dogId().equals(call.getArgument(1))).toList());
        when(repository.forBooking(any())).thenAnswer(call -> stored.values().stream()
                .filter(p -> p.movements().stream().anyMatch(m -> Objects.equals(m.bookingId(), call.getArgument(0)))).findFirst());
        when(repository.forPayment(any())).thenAnswer(call -> stored.values().stream()
                .filter(p -> Objects.equals(p.upfrontPaymentId(), call.getArgument(0))).findFirst());
    }

    @AfterEach void tearDown() { scope.close(); }

    void packs(boolean on) {
        when(configs.get("club")).thenReturn(new ClubConfig(null, Map.of("billing.packLowBalanceSessions", 1),
                on ? Set.of(Module.PACKS) : Set.of(), null, Map.of()));
    }

    PackBalance open(String payment, int total, LocalDate expiry) {
        return service.open("member", "dog", "plan", payment, OPENED, total, expiry, "Gift");
    }

    // --- openMigrated (S18 R-18-10) -----------------------------------------------------------------------------------------

    @Test void T_18_15_aMigratedPackWithNothingConsumedOpensActiveWithoutAnExpiryInstant() {
        service.openMigrated("mig-1", "playoff-1", "member", "dog", "plan", OPENED, 0, LocalDate.of(2026, 7, 1));

        var pack = stored.get("mig-1");
        // consumed = 0 is valid (kills `consumed <= 0`); an ACTIVE pack has no expiredAt (kills the negated EXPIRED check).
        assertThat(pack.remaining()).isEqualTo(10);
        assertThat(pack.state()).isEqualTo(PackBalanceState.ACTIVE);
        assertThat(pack.expiredAt()).isNull();
        assertThat(pack.sourceIds()).isEqualTo(Map.of("playoffPackId", "playoff-1"));
    }

    @Test void T_18_15_aFullyConsumedMigratedPackOpensClosedAndAnExpiredOneCarriesItsInstant() {
        service.openMigrated("mig-2", "playoff-2", "member", "dog", "plan", OPENED, 10, LocalDate.of(2026, 7, 1));
        service.openMigrated("mig-3", "playoff-3", "member", "dog", "plan", LocalDate.of(2025, 1, 10), 6, LocalDate.of(2026, 7, 1));

        // remaining = 0 is valid (kills `remaining <= 0`).
        assertThat(stored.get("mig-2").remaining()).isZero();
        assertThat(stored.get("mig-2").state()).isEqualTo(PackBalanceState.CLOSED);
        assertThat(stored.get("mig-2").expiredAt()).isNull();
        assertThat(stored.get("mig-3").state()).isEqualTo(PackBalanceState.EXPIRED);
        assertThat(stored.get("mig-3").expiredAt()).isEqualTo(NOW);
    }

    // --- list / get / open ----------------------------------------------------------------------------------------------------

    @Test void T_12_22_withPacksOnTheBalancesOfADogAreListed() {
        var pack = open("payment-1", 10, null);
        assertThat(service.list("member", "dog")).extracting(PackBalance::id).containsExactly(pack.id());
    }

    @Test void T_12_07_anUnknownPackIsNotFound() {
        assertThatThrownBy(() -> service.get("missing")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_12_16_aPaidPackPaymentOpensOnePackOnlyOnce() {
        when(census.packPlan("member", "dog")).thenReturn("plan");

        service.open("member", "dog", "payment-1", OPENED);
        service.open("member", "dog", "payment-1", OPENED);

        // The first call opens it (kills the negated `forPayment(...).isPresent()`), the second finds it.
        assertThat(stored.values()).singleElement().satisfies(p -> {
            assertThat(p.upfrontPaymentId()).isEqualTo("payment-1");
            assertThat(p.remaining()).isEqualTo(10);
        });
        verify(repository, times(1)).insert(any());
        verify(events, times(1)).publish(eq(BillingEvent.Kind.PackOpened), any(), any());
    }

    @Test void T_12_16_aPaymentOfAnUnknownMemberIsNotFound() {
        when(census.member("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open("ghost", "dog", "payment-2", OPENED)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_12_07_aPackOfZeroSessionsIsRejected() {
        assertThatThrownBy(() -> open(null, 0, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(repository, never()).insert(any());
    }

    // --- balance / consume ----------------------------------------------------------------------------------------------------

    @Test void T_12_07_aPackMemberWithoutAnyPackHasAnEmptyBalanceNotNone() {
        assertThat(service.balance("member", "dog", CLASS_DATE)).contains(new PackBalanceService.Balance(null, 0, 0, 0, null));
    }

    @Test void T_12_07_aConsumptionPublishesPackConsumedWithItsBookingAndBumpsTheVersion() {
        var pack = open("payment-1", 10, null);

        service.consume("member", "dog", "booking-1", CLASS_DATE);

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(BillingEvent.Kind.PackConsumed), eq(pack.id()), payload.capture());
        assertThat(payload.getValue()).containsEntry("bookingId", "booking-1").containsEntry("remaining", 9);
        // 9 sessions left is above the threshold of 1: no low-balance warning (kills the negated `if (low)`).
        verify(events, never()).publish(eq(BillingEvent.Kind.PackLowBalance), any(), any());
        // write() saves version + 1 against the version it read.
        assertThat(service.get(pack.id()).version()).isEqualTo(1L);
        verify(repository).save(any(), eq(0L));
    }

    @Test void T_12_07_anOpeningEventCarriesNoBookingKey() {
        open("payment-1", 10, null);

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(BillingEvent.Kind.PackOpened), any(), payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("bookingId");
    }

    // --- refund ---------------------------------------------------------------------------------------------------------------

    @Test void T_12_07_aRefundAnswersTheIdOfItsRefundMovement() {
        var pack = open("payment-1", 10, null);
        service.consume("member", "dog", "booking-1", CLASS_DATE);

        String refund = service.refund("booking-1");

        var last = service.get(pack.id()).movements().getLast();
        assertThat(last.type()).isEqualTo(PackMovementType.REFUND);
        assertThat(refund).isNotEmpty().isEqualTo(last.id());
    }

    // --- adjust ---------------------------------------------------------------------------------------------------------------

    @Test void T_12_07_anAdjustmentDownToExactlyZeroIsAllowedAndPublishesDeltaAndReason() {
        var pack = open(null, 2, null);

        var adjusted = service.adjust(pack.id(), -2, "Correction", null);

        assertThat(adjusted.remaining()).isZero();
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(BillingEvent.Kind.PackAdjusted), eq(pack.id()), payload.capture());
        assertThat(payload.getValue()).containsEntry("delta", -2).containsEntry("reason", "Correction");
    }

    // --- warnExpiry (S15 R-15-15a, P5a) ---------------------------------------------------------------------------------------

    @Test void T_15_19_aPackExpiringWithinTheWindowIsWarnedOnceWithTheNextVersion() {
        var pack = open("payment-1", 10, LocalDate.of(2026, 6, 20));

        assertThat(service.warnExpiry(pack.id(), OPENED, 14)).isTrue();
        assertThat(stored.get(pack.id()).version()).isEqualTo(1L);
        assertThat(stored.get(pack.id()).expiryWarnedAt()).isEqualTo(NOW);
        // Already warned: false and nothing written again.
        assertThat(service.warnExpiry(pack.id(), OPENED, 14)).isFalse();
        verify(repository, times(1)).save(any(), eq(0L));
        verify(events, times(1)).publish(eq(BillingEvent.Kind.PackExpiring), any(), any());
    }

    @Test void T_15_19_aPackExpiringAfterTheWindowIsNotWarned() {
        var pack = open("payment-1", 10, null);

        assertThat(service.warnExpiry(pack.id(), OPENED, 14)).isFalse();
        verify(repository, never()).save(any(), any());
    }

    @Test void T_15_19_withPacksOffNothingIsWarned() {
        packs(false);

        assertThat(service.warnExpiry("any-pack", OPENED, 14)).isFalse();
        verifyNoInteractions(repository, events);
    }

    static PackBalance withVersion(PackBalance p, long version) {
        return new PackBalance(p.id(), p.clubId(), p.memberId(), p.dogId(), p.planId(), p.upfrontPaymentId(), p.sessionsTotal(), p.consumed(),
                p.remaining(), p.openedOn(), p.expiresOn(), p.state(), p.movements(), p.expiryWarnedAt(), p.expiredAt(), p.lowBalanceNotifiedAt(),
                p.sourceIds(), version, p.createdAt(), p.createdByAccountId());
    }
}
