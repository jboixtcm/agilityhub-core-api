package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingLockRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceState;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.BillingCensusAccess;
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
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SEPA-04 (b), S12 R-12-17/19 (owner decision of 13-11-2026): the returns of one remittance marked together, all or none, with one
 * summary event for N-10 instead of one notice per receipt; and `includeInNextRun`, which only a periodic receipt may take.
 */
class InvoiceReturnsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final BillingEvents events = mock(BillingEvents.class);
    final AuditWriter audit = mock(AuditWriter.class);
    final ClubClock clubClock = mock(ClubClock.class);
    final InvoiceActions actions = new InvoiceActions(invoices, collections, mock(BillingLockRepository.class), mock(InvoiceNumbers.class),
            mock(InvoicingService.class), mock(BillingCensusAccess.class), mock(BillingTexts.class), events, audit, clubClock, Clock.fixed(NOW, ZoneOffset.UTC));
    TenantContext.Scope tenant;

    @BeforeEach void stubs() {
        tenant = TenantContext.open(CLUB);
        when(clubClock.now(CLUB)).thenReturn(NOW.atZone(ZoneId.of("Europe/Madrid")));
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);
    }
    @AfterEach void close() { tenant.close(); }

    @Test void SEPA_04_b_returnsOfOneRemittanceAreMarkedTogetherWithOneSummaryEvent() {
        stub(invoice("inv-1", InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC), "remittance-1");
        stub(invoice("inv-2", InvoiceStatus.PAID, InvoiceKind.PERIODIC), "remittance-1");

        var returned = actions.markReturned("remittance-1", List.of("inv-1", "inv-2", "inv-1"), "Devolució del banc", TODAY, true);

        assertThat(returned).hasSize(2);
        var states = ArgumentCaptor.forClass(InvoiceState.class);
        verify(invoices, times(2)).transition(anyString(), anyLong(), states.capture());
        assertThat(states.getAllValues()).allSatisfy(state -> {
            assertThat(state.status()).isEqualTo(InvoiceStatus.FAILED);
            assertThat(state.failureReason()).isEqualTo("Devolució del banc");
            assertThat(state.includeInNextRun()).isTrue();
        });
        var appended = ArgumentCaptor.forClass(Collection.class);
        verify(collections, times(2)).insert(appended.capture());
        assertThat(appended.getAllValues()).allSatisfy(attempt -> {
            assertThat(attempt.status()).isEqualTo(CollectionStatus.FAILED);
            assertThat(attempt.failureCode()).isEqualTo(InvoiceActions.BANK_RETURN);
            assertThat(attempt.remittanceId()).isEqualTo("remittance-1");
        });
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(events, times(2)).publish(eq(BillingEvent.Kind.InvoiceFailed), anyString(), payloads.capture());
        assertThat(payloads.getAllValues()).allSatisfy(payload -> assertThat(payload).containsEntry("summarized", true));
        verify(events).publish(BillingEvent.Kind.InvoicesFailed, "remittance-1", Map.of("remittanceId", "remittance-1", "invoiceIds", List.of("inv-1", "inv-2"),
                "provider", "SEPA_XML", "reason", InvoiceActions.BANK_RETURN));
        var entries = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit, times(2)).write(entries.capture());
        assertThat(entries.getAllValues()).extracting(AuditCommand::action).containsOnly(AuditAction.INVOICE_MARKED_FAILED);
    }

    @Test void SEPA_04_b_aReceiptOfAnotherRemittanceOrInAnotherStateRefusesTheWholeRequest() {
        stub(invoice("inv-1", InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC), "remittance-1");
        stub(invoice("inv-other", InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC), "remittance-2");
        stub(invoice("inv-failed", InvoiceStatus.FAILED, InvoiceKind.PERIODIC), "remittance-1");
        assertRefused(() -> actions.markReturned("remittance-1", List.of("inv-1", "inv-other"), "x", TODAY, null), ErrorCode.VALIDATION_ERROR);
        assertRefused(() -> actions.markReturned("remittance-1", List.of("inv-1", "inv-failed"), "x", TODAY, null), ErrorCode.INVALID_STATE);
        // A day after today, checked before anything is written.
        assertRefused(() -> actions.markReturned("remittance-1", List.of("inv-1"), "x", TODAY.plusDays(1), null), ErrorCode.VALIDATION_ERROR);
        verify(collections, never()).insert(any());
        verify(invoices, never()).transition(anyString(), anyLong(), any());
        verifyNoInteractions(events, audit);
    }

    @Test void SEPA_04_b_onlyAPeriodicReceiptWaitsForTheNextRemittance() {
        stub(invoice("inv-1", InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC), "remittance-1");
        stub(invoice("inv-manual", InvoiceStatus.COLLECTING, InvoiceKind.MANUAL), "remittance-1");
        assertRefused(() -> actions.markReturned("remittance-1", List.of("inv-1", "inv-manual"), "x", TODAY, true), ErrorCode.VALIDATION_ERROR);
        assertRefused(() -> actions.markFailed("inv-manual", "x", TODAY, 3L, true), ErrorCode.VALIDATION_ERROR);
        verify(invoices, never()).transition(anyString(), anyLong(), any());
        // Absent or false: the receipt waits for no remittance (a receipt that had re-entered one too), and N-10 is its own.
        actions.markFailed("inv-1", "Devolució del banc", TODAY, 3L, null);
        actions.markFailed("inv-manual", "Devolució del banc", TODAY, 3L, false);
        var states = ArgumentCaptor.forClass(InvoiceState.class);
        verify(invoices, times(2)).transition(anyString(), anyLong(), states.capture());
        assertThat(states.getAllValues()).allSatisfy(state -> assertThat(state.includeInNextRun()).isFalse());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(events, times(2)).publish(eq(BillingEvent.Kind.InvoiceFailed), anyString(), payloads.capture());
        assertThat(payloads.getAllValues()).allSatisfy(payload -> assertThat(payload).doesNotContainKey("summarized"));
        verify(events, never()).publish(eq(BillingEvent.Kind.InvoicesFailed), anyString(), any());
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    void stub(Invoice invoice, String remittanceId) {
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        var attempt = new Collection("c-" + invoice.id(), CLUB, invoice.id(), CollectionProvider.SEPA_XML, invoice.total(), CollectionStatus.CREATED, null, remittanceId,
                1, null, null, List.of(), NOW, null, "MANDATE-1", invoice.displayNumber(), null, null, NOW);
        when(collections.forInvoice(invoice.id())).thenReturn(List.of(attempt));
    }
    static void assertRefused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    static Invoice invoice(String id, InvoiceStatus status, InvoiceKind kind) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, CLUB, "2026", 7, "2026-0007", "2026-09-01", "2026-09", "member-a", new Invoice.MemberSnapshot(1, "Laura Example", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "ES** **** 1234", "Laura Example", "MANDATE-1", null, null, NOW),
                status, kind, kind == InvoiceKind.PERIODIC ? "run-1" : null, "remittance-1", false, null, null, null, null, null, null, new Money(0, "EUR"), null,
                3L, NOW, null, NOW, null);
    }
}
