package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link ManualUpfrontPayments} (S12 R-12-23 manual registration from D10; T-12-07, T-12-21): zero
 * amounts are valid, a non-pack concept is recorded without the PACKS module, the recording is published and shown with its MANUAL
 * provider, and the list filters by status, newest first, with no refunds shown as an empty list.
 */
class ManualUpfrontPaymentsSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");
    static final LocalDate PAID_ON = LocalDate.of(2026, 9, 14);

    final UpfrontPaymentRepository payments = mock(UpfrontPaymentRepository.class);
    final PackBalanceService packs = mock(PackBalanceService.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingEvents events = mock(BillingEvents.class);
    final ClubClock local = mock(ClubClock.class);
    final ManualUpfrontPayments service = new ManualUpfrontPayments(payments, packs, census, mock(BillingCatalogAccess.class), mock(PaymentAudits.class),
            events, Clock.fixed(NOW, ZoneOffset.UTC), local);

    @BeforeEach void stubs() {
        when(census.member("member-a")).thenReturn(Optional.of(member("member-a")));
        when(local.now(CLUB)).thenReturn(NOW.atZone(ZoneId.of("Europe/Madrid")));
        when(payments.insert(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void T_12_07_aManualPaymentIsPublishedAndShownWithItsManualProvider() {
        Map<String, Object> view;
        try (var tenant = TenantContext.open(CLUB)) {
            view = service.record("member-a", null, "ENTRY_FEE", eur(3000), eur(3000), "BIZUM", PAID_ON, "BZ-0001", null);
        }
        String id = (String) view.get("id");
        verify(events).publish(BillingEvent.Kind.UpfrontPaymentRecorded, id,
                Map.of("paymentId", id, "memberId", "member-a", "concept", "ENTRY_FEE", "provider", "MANUAL", "amountPaid", eur(3000)));
        assertThat(view).containsEntry("status", "PAID");
        @SuppressWarnings("unchecked") var provider = (Map<String, Object>) view.get("provider");
        assertThat(provider).containsEntry("type", "MANUAL").containsEntry("channel", "BIZUM").containsEntry("reference", "BZ-0001");
    }

    @Test void T_12_07_zeroAmountsAreValidAndOnlyANegativeOneIsRefused() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(service.record("member-a", null, "ENTRY_FEE", eur(0), eur(0), "CASH", PAID_ON, null, null)).containsEntry("status", "PAID");
            assertThat(service.record("member-a", null, "ENTRY_FEE", eur(3000), eur(0), "CASH", PAID_ON, null, null)).containsEntry("status", "DUE");
            assertCode(() -> service.record("member-a", null, "ENTRY_FEE", eur(-1), eur(0), "CASH", PAID_ON, null, null), ErrorCode.VALIDATION_ERROR);
            assertCode(() -> service.record("member-a", null, "ENTRY_FEE", eur(3000), eur(-1), "CASH", PAID_ON, null, null), ErrorCode.VALIDATION_ERROR);
        }
    }

    @Test void T_12_21_anUnknownMemberIsNotFound() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertCode(() -> service.record("member-unknown", null, "ENTRY_FEE", eur(3000), eur(3000), "CASH", PAID_ON, null, null), ErrorCode.NOT_FOUND);
        }
        verify(payments, never()).insert(any());
    }

    @Test void T_12_07_theListFiltersByStatusNewestFirstAndShowsNoRefundsAsAnEmptyList() {
        // Only a Stripe-paid row can carry a refund (PaymentRefunds:54-58): paid by Checkout, part of it refunded.
        var refund = new UpfrontPayment.Refund(eur(500), "re_fake_1", NOW, "Pagament duplicat", null);
        var paid = new UpfrontPayment("payment-paid", CLUB, "member-a", null, "ENTRY_FEE", "ENTRY_FEE", eur(3000), eur(3000), "PAID", "STRIPE", "cs_fake_1",
                NOW.minusSeconds(3600), NOW.minusSeconds(3600), null, null, null, null, null, new UpfrontPayment.StripeRefs("pi_fake_1", "ch_fake_1"), null, null,
                List.of(refund), null);
        // A signup row written before S12: no refunds at all.
        var partial = new UpfrontPayment("payment-partial", CLUB, "member-a", null, "FIRST_MONTH", "FIRST_MONTH", eur(4500), eur(1000), "PARTIAL", "MANUAL",
                null, NOW, NOW, null, null, null);
        when(payments.member("member-a")).thenReturn(List.of(paid, partial));

        var all = service.list("member-a", null);
        assertThat(all.stream().map(row -> row.get("id")).toList()).containsExactly("payment-partial", "payment-paid");
        assertThat(all.get(0).get("refunds")).isEqualTo(List.of());
        assertThat(all.get(1).get("refunds")).isEqualTo(List.of(refund));
        assertThat(service.list("member-a", "PAID").stream().map(row -> row.get("id")).toList()).containsExactly("payment-paid");
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    static Money eur(long amount) { return new Money(amount, "EUR"); }

    static BillingMember member(String id) {
        var method = new BillingCensusAccess.PaymentMethod("MANUAL", false, null, "Laura Serra", null, null, null, false, "cash", null);
        return new BillingMember(id, 1, "Laura", "Serra", "Puig", "ACTIVE", "plan-monthly", PAID_ON, null, null, method, "00000000T", "ca", NOW);
    }
}
