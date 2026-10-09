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
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobItem;
import com.agilityhub.core.platform.application.jobs.JobRunRecorder;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BillingExpirations} (S15 P5a/P5i; T-15-23, T-12-24): a remittance is settled only when it is
 * submitted, due and still has `COLLECTING` receipts with an open SEPA attempt of its own; each settled attempt is resolved;
 * an item that is no longer in scope has no effect.
 */
class BillingExpirationsSurvivorsTest {
    static final String CLUB = "club-a";
    static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

    final PackBalanceRepository packs = mock(PackBalanceRepository.class);
    final RemittanceRepository remittances = mock(RemittanceRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final BillingExpirations expirations = new BillingExpirations(packs, mock(PackBalanceService.class), remittances, invoices, collections,
            mock(BillingEvents.class), Clock.fixed(NOW, ZoneOffset.UTC));
    final JobContext context = new JobContext(CLUB, ZoneOffset.UTC, NOW, TODAY, false, null, mock(JobRunRecorder.class), "job-run-1");

    @Test void T_15_23_aRemittanceNotSubmittedOrWithNothingLeftToSettleIsNotPlanned() {
        when(remittances.findAll()).thenReturn(List.of(
                remittance("remittance-generated", RemittanceStatus.GENERATED, List.of("c-generated")),
                remittance("remittance-settled", RemittanceStatus.SUBMITTED, List.of("c-done"))));
        stub(attempt("c-generated", "remittance-generated", CollectionStatus.CREATED), invoice("inv-generated", InvoiceStatus.COLLECTING, "remittance-generated"));
        stub(attempt("c-done", "remittance-settled", CollectionStatus.SUCCEEDED), invoice("inv-done", InvoiceStatus.PAID, "remittance-settled"));
        assertThat(expirations.remittances(context)).isEmpty();
    }

    /**
     * A remittance lists the CREATED SEPA_XML attempts the run wrote (SepaRemittanceWriter:113-114). Marking a receipt unpaid
     * appends a FAILED{BANK_RETURN} document and leaves the listed attempt CREATED (InvoiceActions:90-93); that receipt can then
     * be paid by hand (R-12-16). So a listed attempt that is still open can belong to a receipt that is no longer collecting.
     */
    @Test void T_15_23_onlyCollectingReceiptsWithAnOpenAttemptOfTheRemittanceAreCounted() {
        when(remittances.findAll()).thenReturn(List.of(remittance("remittance-1", RemittanceStatus.SUBMITTED, List.of("c-open", "c-paid"))));
        stub(attempt("c-open", "remittance-1", CollectionStatus.CREATED), invoice("inv-open", InvoiceStatus.COLLECTING, "remittance-1"));
        // Returned by the bank, then paid by hand: its listed attempt is still CREATED, but there is nothing left to settle.
        stub(attempt("c-paid", "remittance-1", CollectionStatus.CREATED), invoice("inv-paid", InvoiceStatus.PAID, "remittance-1"));
        assertThat(expirations.remittances(context)).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("SETTLE");
            assertThat(item.detail().get("invoices")).isEqualTo(1);
        });
    }

    /**
     * A settled receipt can be collecting again while its remittance stays SUBMITTED and due: settled on the collection day
     * (listed attempt SUCCEEDED, receipt PAID, BillingExpirations:63-66), later returned by the bank (R-12-17 accepts a PAID
     * receipt with a SEPA attempt: a new FAILED document, receipt FAILED, its remittanceId kept, InvoiceActions:85-93), then
     * charged again to the member's card (R-12-18: CardPayments.retry:73-102 takes any FAILED receipt, appends a STRIPE attempt
     * and moves it to COLLECTING, the remittanceId still kept). Its listed attempt is closed: there is nothing to settle twice.
     */
    @Test void T_15_23_aSettledAttemptWhoseReceiptIsCollectingAgainByCardIsNotSettledAgain() {
        var remittance = remittance("remittance-1", RemittanceStatus.SUBMITTED, List.of("c-open", "c-settled"));
        when(remittances.findAll()).thenReturn(List.of(remittance));
        when(remittances.findById("remittance-1")).thenReturn(Optional.of(remittance));
        stub(attempt("c-open", "remittance-1", CollectionStatus.CREATED), invoice("inv-open", InvoiceStatus.COLLECTING, "remittance-1"));
        stub(attempt("c-settled", "remittance-1", CollectionStatus.SUCCEEDED), invoice("inv-retried-by-card", InvoiceStatus.COLLECTING, "remittance-1"));
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);

        assertThat(expirations.remittances(context)).singleElement().satisfies(item -> assertThat(item.detail().get("invoices")).isEqualTo(1));
        var effect = expirations.apply(context, new JobItem("Remittance", "remittance-1", "SETTLE"));

        assertThat(effect.counters()).isEqualTo(Map.of("settledInvoices", 1L));
        verify(collections, never()).resolve(eq("c-settled"), any(), any(), any());
        verify(invoices, never()).transition(eq("inv-retried-by-card"), anyLong(), any());
    }

    @Test void T_15_23_settlingResolvesEachAttemptAsSucceededOnTheCollectionDay() {
        when(remittances.findById("remittance-1")).thenReturn(Optional.of(remittance("remittance-1", RemittanceStatus.SUBMITTED, List.of("c-open"))));
        stub(attempt("c-open", "remittance-1", CollectionStatus.CREATED), invoice("inv-open", InvoiceStatus.COLLECTING, "remittance-1"));
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);

        var effect = expirations.apply(context, new JobItem("Remittance", "remittance-1", "SETTLE"));

        assertThat(effect.counters()).isEqualTo(Map.of("settledInvoices", 1L));
        verify(collections).resolve("c-open", CollectionStatus.SUCCEEDED, null, Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test void T_12_24_anItemNoLongerInScopeHasNoEffect() {
        var unknownRemittance = expirations.apply(context, new JobItem("Remittance", "remittance-unknown", "SETTLE"));
        assertThat(unknownRemittance).isNotNull();
        assertThat(unknownRemittance.action()).isEqualTo("NOT_IN_SCOPE");
        var unknownPack = expirations.apply(context, new JobItem("PackBalance", "pack-unknown", "EXPIRE_PACK"));
        assertThat(unknownPack).isNotNull();
        assertThat(unknownPack.action()).isEqualTo("NOT_IN_SCOPE");
        assertThat(unknownPack.counters()).isEmpty();
    }

    private void stub(Collection attempt, Invoice invoice) {
        var linked = new Collection(attempt.id(), CLUB, invoice.id(), attempt.provider(), attempt.amount(), attempt.status(), null, attempt.remittanceId(),
                1, null, null, List.of(), NOW, null);
        when(collections.findById(attempt.id())).thenReturn(Optional.of(linked));
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
    }

    static Collection attempt(String id, String remittanceId, CollectionStatus status) {
        return new Collection(id, CLUB, null, CollectionProvider.SEPA_XML, new Money(4500, "EUR"), status, null, remittanceId, 1, null, null, List.of(), NOW, null);
    }

    static Remittance remittance(String id, RemittanceStatus status, List<String> collectionIds) {
        return new Remittance(id, CLUB, "run-1", "2026-09", "club-a-2026-09-1", NOW, TODAY.toString(), null, collectionIds, collectionIds.size(),
                new Money(0, "EUR"), null, "remittances/" + id + ".xml", NOW, null, status, null, null, 0L, NOW, null);
    }

    static Invoice invoice(String id, InvoiceStatus status, String remittanceId) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, CLUB, "2026", 1, "2026-0001", "2026-08-25", "2026-09", "member-a", new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "ES** **** 1234", "Laura Serra", "MANDATE-1", null, null),
                status, InvoiceKind.PERIODIC, "run-1", remittanceId, false, null, null, null, null, null, null, new Money(0, "EUR"), null, 0L, NOW, null, NOW, null);
    }
}
