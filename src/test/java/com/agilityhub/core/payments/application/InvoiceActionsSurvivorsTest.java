package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.domain.ManualChannel;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingLockRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link InvoiceActions} (S12 R-12-16/17/19/27, R-12-09; T-12-05, T-12-14, T-12-20, T-12-21, T-12-30):
 * the member's receipt language, the all-or-none bulk «Marcar cobrat», which receipts a bank return applies to, a manual line
 * in another currency, and the 404s. Collaborators are mocks; fictional members only.
 */
class InvoiceActionsSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    static final Instant SIGNED = Instant.parse("2025-01-10T09:00:00Z");
    static final String REASON = "Retornat pel banc";

    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final InvoiceNumbers numbers = mock(InvoiceNumbers.class);
    final InvoicingService invoicing = mock(InvoicingService.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingEvents events = mock(BillingEvents.class);
    final ClubClock clubClock = mock(ClubClock.class);
    final InvoiceActions actions = new InvoiceActions(invoices, collections, mock(BillingLockRepository.class), numbers, invoicing, census,
            mock(BillingTexts.class), events, mock(AuditWriter.class), clubClock, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void stubs() {
        when(clubClock.now(CLUB)).thenReturn(NOW.atZone(ZoneId.of("Europe/Madrid")));
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);
    }

    @Test void T_12_20_theReceiptLanguageIsTheMembersOwnAndNoneWhenUnknownOrBlank() {
        when(census.member("member-ca")).thenReturn(Optional.of(member("member-ca", "ca")));
        when(census.member("member-none")).thenReturn(Optional.of(member("member-none", null)));
        when(census.member("member-blank")).thenReturn(Optional.of(member("member-blank", "  ")));
        assertThat(actions.memberLocale("member-ca")).contains("ca");
        assertThat(actions.memberLocale("member-none")).isEmpty();
        assertThat(actions.memberLocale("member-blank")).isEmpty();
    }

    @Test void T_12_21_anUnknownReceiptOrMemberIsNotFound() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertCode(() -> actions.detail("invoice-unknown"), ErrorCode.NOT_FOUND);
            assertCode(() -> actions.markPaid("invoice-unknown", TODAY, ManualChannel.CASH, null, 0L), ErrorCode.NOT_FOUND);
            assertCode(() -> actions.markPaid(List.of("invoice-unknown"), TODAY, ManualChannel.CASH), ErrorCode.NOT_FOUND);
            assertCode(() -> actions.createManual("member-unknown", List.of(), false, null), ErrorCode.NOT_FOUND);
        }
    }

    @Test void T_12_14_aBulkMarkPaidChecksEveryReceiptBeforePayingAny() {
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", InvoiceStatus.PENDING, PaymentMethodType.MANUAL)));
        when(invoices.findById("invoice-2")).thenReturn(Optional.of(invoice("invoice-2", InvoiceStatus.PAID, PaymentMethodType.MANUAL)));
        try (var tenant = TenantContext.open(CLUB)) {
            assertCode(() -> actions.markPaid(List.of("invoice-1", "invoice-2"), TODAY, ManualChannel.CASH), ErrorCode.INVALID_STATE);
        }
        // R-12-16: all or none — the payable first receipt is not paid either.
        verify(collections, never()).insert(any());
        verify(invoices, never()).transition(anyString(), anyLong(), any());
        verifyNoInteractions(events);
    }

    @Test void T_12_14_onlyASepaAttemptMakesAPaidReceiptReturnable() {
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", InvoiceStatus.PAID, PaymentMethodType.MANUAL)));
        // Paid by hand: its only attempt is MANUAL, so there is no bank to return it.
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(attempt("collection-1", "invoice-1", CollectionProvider.MANUAL, CollectionStatus.SUCCEEDED)));
        try (var tenant = TenantContext.open(CLUB)) {
            assertCode(() -> actions.markFailed("invoice-1", REASON, TODAY, 3L), ErrorCode.INVALID_STATE);
        }
        verify(collections, never()).insert(any());
    }

    /**
     * The COLLECTING receipts the code writes: a SEPA_DD one with its SEPA_XML attempt in the remittance (BillingRunService:108-114,
     * 119-123) and a CARD one with its submitted STRIPE attempt (CardPayments:99-102); the frozen method never changes
     * (`InvoiceState` has no payment method), so a SEPA attempt and the SEPA_DD method always come together.
     */
    @Test void T_12_30_aCollectingSepaReceiptIsReturnableAndACollectingCardOneIsNot() {
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", InvoiceStatus.COLLECTING, PaymentMethodType.SEPA_DD)));
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(attempt("collection-1", "invoice-1", CollectionProvider.SEPA_XML, CollectionStatus.CREATED)));
        // A collecting card receipt has no bank return.
        when(invoices.findById("invoice-3")).thenReturn(Optional.of(invoice("invoice-3", InvoiceStatus.COLLECTING, PaymentMethodType.CARD)));
        when(collections.forInvoice("invoice-3")).thenReturn(List.of(attempt("collection-3", "invoice-3", CollectionProvider.STRIPE, CollectionStatus.SUBMITTED)));
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(actions.markFailed("invoice-1", REASON, TODAY, 3L).invoice().id()).isEqualTo("invoice-1");
            assertCode(() -> actions.markFailed("invoice-3", REASON, TODAY, 3L), ErrorCode.INVALID_STATE);
        }
        var inserted = ArgumentCaptor.forClass(Collection.class);
        verify(collections).insert(inserted.capture());
        assertThat(inserted.getValue()).extracting(Collection::invoiceId, Collection::status, Collection::failureCode, Collection::remittanceId, Collection::mandateRef)
                .containsExactly("invoice-1", CollectionStatus.FAILED, InvoiceActions.BANK_RETURN, "remittance-1", "MANDATE-1");
    }

    @Test void T_12_05_aManualLineInAnotherCurrencyIsRefusedBeforeANumberIsTaken() {
        when(census.member("member-a")).thenReturn(Optional.of(member("member-a", "ca")));
        when(invoicing.context()).thenReturn(context());
        when(numbers.reserve(any(), anyInt())).thenReturn(new InvoiceNumbers.Block("2026", "2026", 8L));
        var line = new InvoiceActions.ManualLine("Ajust de quota", new Money(1500, "USD"), BigDecimal.ZERO);
        try (var tenant = TenantContext.open(CLUB)) {
            assertCode(() -> actions.createManual("member-a", List.of(line), false, null), ErrorCode.CURRENCY_MISMATCH);
        }
        verifyNoInteractions(numbers);
        verify(invoices, never()).insert(any());
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    static BillingMember member(String id, String locale) {
        var method = new BillingCensusAccess.PaymentMethod("MANUAL", false, null, "Laura Serra", null, null, null, false, "cash", null);
        return new BillingMember(id, 1, "Laura", "Serra", "Puig", "ACTIVE", "plan-monthly", TODAY, null, null, method, "00000000T", locale, NOW);
    }

    static InvoicingService.Context context() {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        var settings = new InvoicingRules.Settings("EUR", 1, InvoicingRules.CashInvoicing.MONTHLY, 6, true, false, false, false, Set.of(PaymentMethodType.MANUAL));
        return new InvoicingService.Context(new ClubConfig(club, Map.of(), Set.of(), null, Map.of()), settings, TODAY, Locale.forLanguageTag("ca"));
    }

    /** A run's receipt with its method frozen as BillingRunService.snapshot freezes it; only a SEPA_DD one is in the remittance. */
    static Invoice invoice(String id, InvoiceStatus status, PaymentMethodType type) {
        var total = new Money(4500, "EUR");
        boolean sepa = type == PaymentMethodType.SEPA_DD;
        var method = new Invoice.PaymentMethodSnapshot(type, type == PaymentMethodType.MANUAL ? null : "ES** **** 1234", "Laura Serra", sepa ? "MANDATE-1" : null,
                type == PaymentMethodType.CARD ? "4242" : null, type == PaymentMethodType.MANUAL ? ManualChannel.CASH : null, sepa ? SIGNED : null);
        return new Invoice(id, CLUB, "2026", 7, "2026-0007", "2026-09-01", "2026-09", "member-a", new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, method,
                status, InvoiceKind.PERIODIC, "run-1", sepa ? "remittance-1" : null, false, null, null, null, null, null, null, new Money(0, "EUR"), null, 3L, NOW, null,
                NOW, null);
    }

    /** An attempt as its writer leaves it: a SEPA_XML one in the remittance with the mandate, the others without either. */
    static Collection attempt(String id, String invoiceId, CollectionProvider provider, CollectionStatus status) {
        boolean sepa = provider == CollectionProvider.SEPA_XML;
        return new Collection(id, CLUB, invoiceId, provider, new Money(4500, "EUR"), status, null, sepa ? "remittance-1" : null, 1, null, null, List.of(), NOW, null,
                sepa ? "MANDATE-1" : null, sepa ? "2026-0007" : null, provider == CollectionProvider.MANUAL ? ManualChannel.CASH : null, null, sepa ? SIGNED : null);
    }
}
