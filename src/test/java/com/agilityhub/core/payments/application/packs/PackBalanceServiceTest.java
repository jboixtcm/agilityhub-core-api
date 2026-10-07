package com.agilityhub.core.payments.application.packs;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackBalanceServiceTest {
    final PackBalanceRepository repository = mock(PackBalanceRepository.class);
    final BillingCatalogAccess catalog = mock(BillingCatalogAccess.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final BillingEvents events = mock(BillingEvents.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-06-12T10:00:00Z"), ZoneOffset.UTC);
    final ClubClock local = mock(ClubClock.class);
    final PackBalanceService service = new PackBalanceService(repository, catalog, census, configs, events, clock, local);
    final Map<String, PackBalance> stored = new LinkedHashMap<>();
    AutoCloseable scope;

    @BeforeEach void setup() {
        scope = TenantContext.open("club");
        when(configs.get("club")).thenReturn(new ClubConfig(null, Map.of("billing.packLowBalanceSessions", 1), Set.of(Module.PACKS), null, Map.of()));
        when(local.today("club")).thenReturn(LocalDate.of(2026, 6, 12));
        when(catalog.pack("plan")).thenReturn(new BillingCatalogAccess.PackTerms("plan", 10, 5));
        planOfMember("PACK");
        when(repository.insert(any())).thenAnswer(call -> { var p = (PackBalance) call.getArgument(0); var saved = version(p, 0L); stored.put(saved.id(), saved); return saved; });
        when(repository.save(any(), any())).thenAnswer(call -> { var p = (PackBalance) call.getArgument(0); stored.put(p.id(), p); return p; });
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.of(any(), any())).thenAnswer(call -> stored.values().stream().filter(p -> p.memberId().equals(call.getArgument(0)) && p.dogId().equals(call.getArgument(1))).toList());
        when(repository.forBooking(any())).thenAnswer(call -> stored.values().stream().filter(p -> p.movements().stream().anyMatch(m -> Objects.equals(m.bookingId(), call.getArgument(0)))).findFirst());
        when(repository.forPayment(any())).thenAnswer(call -> stored.values().stream().filter(p -> Objects.equals(p.upfrontPaymentId(), call.getArgument(0))).findFirst());
    }
    @AfterEach void close() throws Exception { scope.close(); }
    void planOfMember(String type) {
        when(census.member("member")).thenReturn(Optional.of(new BillingCensusAccess.BillingMember("member", 7, "Laura", "Serra", null, "ACTIVE",
                "member-plan", null, null, null, null, null, "ca", null)));
        when(catalog.plan("member-plan")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("member-plan", "P", type, null, 1, null, null)));
    }
    PackBalance open(String payment, int total, LocalDate expiry) {
        return service.open("member", "dog", "plan", payment, LocalDate.of(2026, 6, 12), total, expiry, "Gift");
    }
    @Test void T_12_07_openUsesInclusiveValidityAndIsIdempotentPerPayment() {
        var first = open("payment", 10, null);
        assertThat(first.expiresOn()).isEqualTo("2026-11-11");
        assertThat(first.movements()).singleElement().satisfies(m -> { assertThat(m.type()).isEqualTo(PackMovementType.OPEN); assertThat(m.delta()).isEqualTo(10); });
        assertThat(open("payment", 10, null).id()).isEqualTo(first.id());
        verify(events, times(1)).publish(eq(BillingEvent.Kind.PackOpened), any(), any());
    }
    @Test void T_12_07_whenTwoPacksQualifyTheOneThatExpiresFirstIsConsumed() {
        // Opened in the reverse order of their expiry, so the choice cannot come from the insertion order.
        var last = open("last", 3, LocalDate.of(2026, 12, 31));
        var first = open("first", 3, LocalDate.of(2026, 9, 30));
        var date = LocalDate.of(2026, 8, 1);
        assertThat(service.balance("member", "dog", date).orElseThrow().id()).isEqualTo(first.id());
        service.consume("member", "dog", "booking", date);
        assertThat(service.get(first.id()).remaining()).isEqualTo(2);
        assertThat(service.get(last.id()).remaining()).isEqualTo(3);
        // A class after the first one's expiry falls through to the other pack.
        service.consume("member", "dog", "later-booking", LocalDate.of(2026, 10, 15));
        assertThat(service.get(last.id()).remaining()).isEqualTo(2);
    }
    @Test void T_12_22_aMonthlyMemberWithAnOldPackBooksWithoutPackRules() {
        var old = open("old", 2, LocalDate.of(2026, 7, 1));
        service.expire(old.id());
        planOfMember("PACK");
        assertThat(service.balance("member", "dog", LocalDate.of(2026, 8, 1)).orElseThrow().remaining()).isZero();
        // S08 R-08-17: after a pack → membership change the old rows stay, but a monthly plan is never refused with PACK_EMPTY.
        planOfMember("MONTHLY");
        assertThat(service.balance("member", "dog", LocalDate.of(2026, 8, 1))).isEmpty();
    }
    @Test void T_12_22_monthlyPlanNeverConsumesEvenAUsablePackButCanRefundEarlierBookings() {
        var pack = open("paid", 10, null);
        var date = LocalDate.of(2026, 7, 1);
        service.consume("member", "dog", "before-plan-change", date);
        planOfMember("MONTHLY");
        assertThat(service.balance("member", "dog", date)).isEmpty();
        assertThat(service.consume("member", "dog", "monthly-booking", date)).isNull();
        assertThat(service.get(pack.id()).remaining()).isEqualTo(9);
        assertThat(service.refund("before-plan-change")).isNotNull();
        assertThat(service.get(pack.id()).remaining()).isEqualTo(10);
    }
    @Test void T_12_07_T_12_31_consumesEarliestQualifyingPackAndWarnsOnlyOnce() {
        var early = open("early", 2, LocalDate.of(2026, 7, 1));
        var later = open("later", 2, LocalDate.of(2026, 11, 1));
        var date = LocalDate.of(2026, 8, 1);
        assertThat(service.balance("member", "dog", date).orElseThrow().id()).isEqualTo(later.id());
        String movement = service.consume("member", "dog", "booking", date);
        assertThat(service.consume("member", "dog", "booking", date)).isEqualTo(movement);
        assertThat(service.get(early.id()).remaining()).isEqualTo(2);
        assertThat(service.get(later.id()).remaining()).isEqualTo(1);
        service.refund("booking");
        service.consume("member", "dog", "second", date);
        verify(events, times(1)).publish(eq(BillingEvent.Kind.PackLowBalance), any(), any());
        service.consume("member", "dog", "third", date);
        assertThatThrownBy(() -> service.consume("member", "dog", "fourth", date)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PACK_EMPTY));
    }
    @Test void T_12_31_refundAfterExpiryIsRecordedOnceAndRemainsUnusable() {
        var p = open("paid", 10, null);
        assertThat(service.refund("missing")).isNull();
        service.consume("member", "dog", "booking", LocalDate.of(2026, 7, 1));
        service.expire(p.id()); service.expire(p.id());
        assertThat(service.refund("booking")).isNotNull();
        assertThat(service.refund("booking")).isNull();
        var expired = service.get(p.id());
        assertThat(expired.state()).isEqualTo(PackBalanceState.EXPIRED);
        assertThat(expired.remaining()).isEqualTo(10);
        assertThat(expired.consumed()).isZero();
        assertThat(service.balance("member", "dog", LocalDate.of(2026, 7, 1)).orElseThrow().remaining()).isZero();
        verify(events, times(1)).publish(eq(BillingEvent.Kind.PackExpired), any(), any());
        verify(events, times(1)).publish(eq(BillingEvent.Kind.PackRefunded), any(), any());
    }
    @Test void T_12_07_adjustRejectsNegativeBalancesAndRequiresANewExpiryToReopen() {
        var p = open(null, 2, null);
        assertThatThrownBy(() -> service.adjust(p.id(), -3, "Correction", null)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PACK_NEGATIVE));
        service.expire(p.id());
        assertThatThrownBy(() -> service.adjust(p.id(), 1, "Reopen", null)).isInstanceOf(ApiException.class);
        var adjusted = service.adjust(p.id(), 1, "Reopen", LocalDate.of(2027, 1, 1));
        assertThat(adjusted.state()).isEqualTo(PackBalanceState.ACTIVE);
        assertThat(adjusted.remaining()).isEqualTo(3);
        assertThat(adjusted.expiredAt()).isNull();
        assertThat(adjusted.movements()).extracting(PackBalance.Movement::type).containsExactly(PackMovementType.OPEN, PackMovementType.EXPIRE, PackMovementType.ADJUST);
    }
    @Test void T_12_22_packsOffDoesNotReadOrWriteBalances() {
        when(configs.get("club")).thenReturn(new ClubConfig(null, Map.of(), Set.of(), null, Map.of()));
        assertThat(service.balance("member", "dog", LocalDate.of(2026, 7, 1))).isEmpty();
        assertThat(service.consume("member", "dog", "booking", LocalDate.of(2026, 7, 1))).isNull();
        assertThat(service.refund("booking")).isNull();
        service.expire("pack"); service.open("member", "dog", "payment", LocalDate.of(2026, 6, 12));
        verifyNoInteractions(repository, events);
    }
    static PackBalance version(PackBalance p, long version) {
        return new PackBalance(p.id(), p.clubId(), p.memberId(), p.dogId(), p.planId(), p.upfrontPaymentId(), p.sessionsTotal(), p.consumed(), p.remaining(), p.openedOn(), p.expiresOn(), p.state(), p.movements(), p.expiryWarnedAt(), p.expiredAt(), p.lowBalanceNotifiedAt(), p.sourceIds(), version, p.createdAt(), p.createdByAccountId());
    }
}
