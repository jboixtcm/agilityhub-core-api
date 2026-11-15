package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.ExportFileStore;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * SEPA-04 (a), S12 R-12-15 (owner decision of 13-11-2026): «sent to the bank» is undone (`SUBMITTED → GENERATED`) only for a
 * submitted remittance, before its collection date (club-local) and while every receipt it carries is as the run left it; the
 * undo is audited with the admin's reason.
 */
class RemittanceSubmissionUndoTest {
    static final String CLUB = "club-a";
    /** 31-08-2026 23:30 in Madrid: the last day before the 01-09 collection date. */
    static final Instant NOW = Instant.parse("2026-08-31T21:30:00Z");

    final RemittanceRepository remittances = mock(RemittanceRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final AuditWriter audit = mock(AuditWriter.class);
    final ClubConfigService configs = mock(ClubConfigService.class, RETURNS_DEEP_STUBS);
    TenantContext.Scope tenant;

    @BeforeEach void open() {
        tenant = TenantContext.open(CLUB);
        ClubConfig config = configs.get(CLUB);
        when(config.club().timeZone()).thenReturn("Europe/Madrid");
    }
    @AfterEach void close() { tenant.close(); }

    RemittanceService service(Instant now) {
        return new RemittanceService(remittances, invoices, collections, mock(ExportFileStore.class), configs, audit, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void SEPA_04_a_aSubmittedRemittanceWhoseReceiptsAreUntouchedGoesBackToGeneratedAndIsAudited() {
        var submitted = remittance(RemittanceStatus.SUBMITTED);
        when(remittances.findById("remittance-1")).thenReturn(Optional.of(submitted), Optional.of(remittance(RemittanceStatus.GENERATED)));
        untouched("c-1", "inv-1"); untouched("c-2", "inv-2");
        when(remittances.undoSubmission("remittance-1")).thenReturn(true);

        var after = service(NOW).undoSubmission("remittance-1", "The bank refused the file");

        assertThat(after.status()).isEqualTo(RemittanceStatus.GENERATED);
        verify(remittances).undoSubmission("remittance-1");
        var command = ArgumentCaptor.forClass(AuditCommand.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).write(command.capture(), details.capture());
        assertThat(command.getValue().action()).isEqualTo(AuditAction.REMITTANCE_SUBMISSION_UNDONE);
        assertThat(command.getValue().reason()).isEqualTo("The bank refused the file");
        assertThat(details.getValue()).containsEntry("submittedOn", "2026-08-26").containsEntry("period", "2026-09").containsEntry("runId", "run-1");
    }

    @Test void SEPA_04_a_onlyASubmittedRemittanceCanBeUndone() {
        for (var status : List.of(RemittanceStatus.GENERATED, RemittanceStatus.ROLLED_BACK)) {
            when(remittances.findById("remittance-1")).thenReturn(Optional.of(remittance(status)));
            assertInvalid(() -> service(NOW).undoSubmission("remittance-1", "Example"), Map.of("status", status.name()));
        }
        verify(remittances, never()).undoSubmission(any());
        verifyNoInteractions(audit);
    }

    @Test void SEPA_04_a_fromTheCollectionDateOnTheSubmissionStays() {
        when(remittances.findById("remittance-1")).thenReturn(Optional.of(remittance(RemittanceStatus.SUBMITTED)));
        untouched("c-1", "inv-1"); untouched("c-2", "inv-2");
        // 01-09-2026 00:00 in Madrid: the collection date itself.
        assertInvalid(() -> service(Instant.parse("2026-08-31T22:00:00Z")).undoSubmission("remittance-1", "Example"), Map.of("reason", "COLLECTION_DATE_PASSED"));
        verify(remittances, never()).undoSubmission(any());
        verifyNoInteractions(audit);
    }

    @Test void SEPA_04_a_aReceiptReturnedPaidOrChargedAgainSinceTheRunKeepsTheSubmission() {
        when(remittances.findById("remittance-1")).thenReturn(Optional.of(remittance(RemittanceStatus.SUBMITTED)));
        untouched("c-1", "inv-1");
        // Marked returned: a FAILED{BANK_RETURN} attempt appended and the receipt FAILED, its listed attempt still CREATED.
        var listed = attempt("c-2", "inv-2", CollectionStatus.CREATED);
        when(collections.findById("c-2")).thenReturn(Optional.of(listed));
        when(collections.forInvoice("inv-2")).thenReturn(List.of(listed, attempt("c-2-failed", "inv-2", CollectionStatus.FAILED)));
        when(invoices.findById("inv-2")).thenReturn(Optional.of(invoice("inv-2", InvoiceStatus.FAILED)));
        assertInvalid(() -> service(NOW).undoSubmission("remittance-1", "Example"), Map.of("reason", "INVOICE_CHANGED", "invoiceIds", List.of("inv-2")));
        // Then charged by card (R-12-18): COLLECTING again, but its last attempt is not the remittance's.
        when(invoices.findById("inv-2")).thenReturn(Optional.of(invoice("inv-2", InvoiceStatus.COLLECTING)));
        when(collections.forInvoice("inv-2")).thenReturn(List.of(listed, attempt("c-2-failed", "inv-2", CollectionStatus.FAILED),
                attempt("c-2-card", "inv-2", CollectionStatus.SUBMITTED)));
        assertInvalid(() -> service(NOW).undoSubmission("remittance-1", "Example"), Map.of("reason", "INVOICE_CHANGED", "invoiceIds", List.of("inv-2")));
        // A listed attempt already resolved (settled) is a change too.
        var settled = attempt("c-2", "inv-2", CollectionStatus.SUCCEEDED);
        when(collections.findById("c-2")).thenReturn(Optional.of(settled));
        when(collections.forInvoice("inv-2")).thenReturn(List.of(settled));
        assertInvalid(() -> service(NOW).undoSubmission("remittance-1", "Example"), Map.of("reason", "INVOICE_CHANGED", "invoiceIds", List.of("inv-2")));
        verify(remittances, never()).undoSubmission(any());
        verifyNoInteractions(audit);
    }

    @Test void SEPA_04_a_aRemittanceThatMovedMeanwhileIsAStaleVersion() {
        when(remittances.findById("remittance-1")).thenReturn(Optional.of(remittance(RemittanceStatus.SUBMITTED)));
        untouched("c-1", "inv-1"); untouched("c-2", "inv-2");
        when(remittances.undoSubmission("remittance-1")).thenReturn(false);
        assertThatThrownBy(() -> service(NOW).undoSubmission("remittance-1", "Example"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
        verifyNoInteractions(audit);
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    void untouched(String collectionId, String invoiceId) {
        var attempt = attempt(collectionId, invoiceId, CollectionStatus.CREATED);
        when(collections.findById(collectionId)).thenReturn(Optional.of(attempt));
        when(collections.forInvoice(invoiceId)).thenReturn(List.of(attempt));
        when(invoices.findById(invoiceId)).thenReturn(Optional.of(invoice(invoiceId, InvoiceStatus.COLLECTING)));
    }
    static void assertInvalid(ThrowingCallable call, Map<String, Object> details) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE);
            assertThat(e.details()).isEqualTo(details);
        });
    }
    static Remittance remittance(RemittanceStatus status) {
        Instant submitted = status == RemittanceStatus.SUBMITTED ? Instant.parse("2026-08-25T22:00:00Z") : null;
        return new Remittance("remittance-1", CLUB, "run-1", "2026-09", "club-a-2026-09-1", Instant.parse("2026-08-25T08:00:00Z"), "2026-09-01", null,
                List.of("c-1", "c-2"), 2, new Money(9000, "EUR"), null, "remittances/remittance-1.xml", Instant.parse("2026-08-25T08:00:00Z"), null, status,
                submitted, submitted == null ? null : "admin-1", 1L, Instant.parse("2026-08-25T08:00:00Z"), "admin-1");
    }
    static Collection attempt(String id, String invoiceId, CollectionStatus status) {
        return new Collection(id, CLUB, invoiceId, status == CollectionStatus.SUBMITTED ? CollectionProvider.STRIPE : CollectionProvider.SEPA_XML,
                new Money(4500, "EUR"), status, null, "remittance-1", 1, null, null, List.of(), NOW, null);
    }
    static Invoice invoice(String id, InvoiceStatus status) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, CLUB, "2026", 1, "2026-0001", "2026-08-25", "2026-09", "member-a", new Invoice.MemberSnapshot(1, "Laura Example", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "···· 1234", "Laura Example", "club-a-1-1", null, null),
                status, InvoiceKind.PERIODIC, "run-1", "remittance-1", false, null, null, null, null, null, null, new Money(0, "EUR"), null,
                0L, NOW, "admin-1", NOW, "admin-1");
    }
}
