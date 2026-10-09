package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.application.ports.RemittanceWriterPort;
import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.BillingIncidentCode;
import com.agilityhub.core.payments.domain.BillingRunStatus;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceAmounts;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.domain.ManualChannel;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingRunRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingSimulationRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.BillingSimulation;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.InvoiceCounters;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BillingRunService} (S12 R-12-07/08/11/12/14/19; T-12-09, T-12-13, T-12-21): what the run
 * freezes on each attempt, what its remittance event and totals carry, whom it skips, and what a rollback copies from the last
 * attempt. Collaborators are mocks; fictional members only.
 */
class BillingRunServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final YearMonth PERIOD = YearMonth.of(2026, 9);
    static final LocalDate ISSUE = LocalDate.of(2026, 8, 25);
    static final LocalDate COLLECTION = LocalDate.of(2026, 9, 1);
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");
    static final Instant SIGNED = Instant.parse("2025-01-10T09:00:00Z");
    static final Instant SIGNED_LATER = Instant.parse("2025-06-10T09:00:00Z");

    final InvoicingService invoicing = mock(InvoicingService.class);
    final BillingRunRepository runs = mock(BillingRunRepository.class);
    final BillingSimulationRepository simulations = mock(BillingSimulationRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final RemittanceRepository remittances = mock(RemittanceRepository.class);
    final RemittanceWriterPort writer = mock(RemittanceWriterPort.class);
    final InvoiceNumbers numbers = mock(InvoiceNumbers.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingEvents events = mock(BillingEvents.class);
    final BillingRunService service = new BillingRunService(invoicing, mock(BillingSimulationService.class), runs, simulations, invoices, collections,
            remittances, mock(PendingChargeRepository.class), writer, mock(InvoiceCounters.class), numbers, census, mock(BillingCatalogAccess.class), events,
            mock(AuditWriter.class), mock(BillingTransactions.class), Clock.fixed(NOW, ZoneOffset.UTC));

    // --- generation ---------------------------------------------------------------------------------------------------------

    @Test void T_12_09_theRunFreezesEachAttemptRemitsOnlyCollectingReceiptsAndSkipsAWaitingIncidentOnce() {
        var members = new LinkedHashMap<String, BillingMember>();
        members.put("member-sepa", member("member-sepa", "SEPA_DD", null));
        members.put("member-cash", member("member-cash", "MANUAL", "bizum"));
        members.put("member-waiting", member("member-waiting", "SEPA_DD", null));
        // The month's incident is an ACTIVE member too (InvoicingService:94-104): the run names it.
        members.put("member-skipped", new BillingMember("member-skipped", 4, "Joan", "Roca", null, "ACTIVE", "plan-monthly", COLLECTION, null, null, null,
                "00000000T", "ca", NOW));
        var drafts = List.of(new InvoicingRules.Draft("member-sepa", List.of(line(4500, null)), List.of()),
                new InvoicingRules.Draft("member-cash", List.of(line(4500, null), line(3000, "member-family")), List.of()));
        var waiting = List.of(new InvoicingService.WaitingReceipt(receipt("receipt-r1", "member-waiting"), "Eva Roca", null),
                // R-12-07: a family member billed on the holder's draft is not skipped for its waiting receipt...
                new InvoicingService.WaitingReceipt(receipt("receipt-r2", "member-family"), "Pau Roca", BillingIncidentCode.NO_BANK_ACCOUNT),
                // ...and a member already skipped with the same incident is listed once.
                new InvoicingService.WaitingReceipt(receipt("receipt-r3", "member-skipped"), "Joan Example", BillingIncidentCode.NO_BANK_ACCOUNT));
        var plan = plan(drafts, List.of(new InvoicingRules.Incident("member-skipped", BillingIncidentCode.NO_BANK_ACCOUNT)), members, waiting);
        generating(plan, List.of("receipt-r1"));

        var outcome = service.generate(PERIOD, "simulation-1", COLLECTION);

        var issued = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices, times(2)).insert(issued.capture());
        var sepaInvoice = issued.getAllValues().stream().filter(invoice -> invoice.memberId().equals("member-sepa")).findFirst().orElseThrow();
        var cashInvoice = issued.getAllValues().stream().filter(invoice -> invoice.memberId().equals("member-cash")).findFirst().orElseThrow();
        var attempts = ArgumentCaptor.forClass(Collection.class);
        verify(collections, times(3)).insert(attempts.capture());
        var sepaAttempt = attempts.getAllValues().stream().filter(attempt -> attempt.invoiceId().equals(sepaInvoice.id())).findFirst().orElseThrow();
        var cashAttempt = attempts.getAllValues().stream().filter(attempt -> attempt.invoiceId().equals(cashInvoice.id())).findFirst().orElseThrow();
        // R-12-12: the SEPA attempt carries the mandate's signature; the manual one, the member's channel.
        assertThat(sepaAttempt.mandateSignedAt()).isEqualTo(SIGNED);
        assertThat(cashAttempt.provider()).isEqualTo(CollectionProvider.MANUAL);
        assertThat(cashAttempt.channel()).isEqualTo(ManualChannel.BIZUM);
        assertThat(cashAttempt.mandateSignedAt()).isNull();

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(BillingEvent.Kind.RemittanceGenerated), anyString(), payload.capture());
        assertThat(payload.getValue().get("invoiceIds")).as("only the COLLECTING invoices and the remitted receipt")
                .isEqualTo(List.of(sepaInvoice.id(), "receipt-r1"));

        assertThat(outcome.remittance()).isNotNull();
        var sepaTotals = outcome.run().byProvider().sepaXml();
        assertThat(sepaTotals.remittanceId()).isEqualTo(outcome.remittance().id());
        assertThat(sepaTotals.count()).isEqualTo(2);
        assertThat(outcome.run().byProvider().manual().remittanceId()).isNull();
        assertThat(outcome.run().skipped()).containsExactly(new BillingRun.Skipped("member-skipped", "Joan Example", BillingIncidentCode.NO_BANK_ACCOUNT));
    }

    @Test void T_12_09_aWaitingReceiptAloneOpensTheSepaTotalsOfAManualRun() {
        var members = new LinkedHashMap<String, BillingMember>();
        members.put("member-cash", member("member-cash", "MANUAL", "cash"));
        members.put("member-waiting", member("member-waiting", "SEPA_DD", null));
        var plan = plan(List.of(new InvoicingRules.Draft("member-cash", List.of(line(4500, null)), List.of())), List.of(), members,
                List.of(new InvoicingService.WaitingReceipt(receipt("receipt-r1", "member-waiting"), "Eva Roca", null)));
        generating(plan, List.of("receipt-r1"));

        var outcome = service.generate(PERIOD, "simulation-1", COLLECTION);

        var sepaTotals = outcome.run().byProvider().sepaXml();
        assertThat(sepaTotals.count()).isEqualTo(1);
        assertThat(sepaTotals.total()).isEqualTo(new Money(2500, "EUR"));
        assertThat(sepaTotals.remittanceId()).isEqualTo(outcome.remittance().id());
    }

    @Test void T_12_21_anUnknownSimulationIsNotFound() {
        assertThatThrownBy(() -> service.generate(PERIOD, "simulation-unknown", COLLECTION)).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class)
                .hasMessage("NOT_FOUND");
    }

    @Test void T_12_09_aChangeOfAPreviewedMemberAfterTheSimulationMakesItStale() {
        var simulation = new BillingSimulation("simulation-1", CLUB, PERIOD.toString(), NOW, List.of(), List.of(),
                List.of(new BillingSimulation.PreviewInvoice("member-preview", "Laura Serra", PaymentMethodType.MANUAL, List.of(), new Money(4500, "EUR"))),
                null, null, null, null);
        when(census.lastChange(Set.of("member-preview"))).thenReturn(Optional.of(NOW.plusSeconds(60)));
        assertThat(service.stale(simulation, plan(List.of(), List.of(), Map.of(), List.of()))).isTrue();
    }

    // --- frozen payment method and payload ----------------------------------------------------------------------------------

    @Test void T_12_09_theFrozenPaymentMethodKeepsOnlyTheFieldsOfItsType() {
        var payment = new BillingCensusAccess.PaymentMethod("SEPA_DD", true, "ES** **** 1234", "Laura Serra", null, "MANDATE-1", "4242", false, "cash", SIGNED);
        var sepa = BillingRunService.snapshot(PaymentMethodType.SEPA_DD, payment);
        assertThat(sepa.mandateRef()).isEqualTo("MANDATE-1");
        assertThat(sepa.mandateSignedAt()).isEqualTo(SIGNED);
        assertThat(sepa.last4()).isNull();
        assertThat(sepa.channel()).isNull();
        var card = BillingRunService.snapshot(PaymentMethodType.CARD, payment);
        assertThat(card.last4()).isEqualTo("4242");
        assertThat(card.mandateSignedAt()).isNull();
        assertThat(card.channel()).isNull();
        var manual = BillingRunService.snapshot(PaymentMethodType.MANUAL, payment);
        assertThat(manual.channel()).isEqualTo(ManualChannel.CASH);
        assertThat(manual.last4()).isNull();
        assertThat(manual.mandateSignedAt()).isNull();
    }

    @Test void T_12_09_aStoredChannelIsReadLenientlyAndAnUnknownOneIsNone() {
        assertThat(BillingRunService.channel(" bizum ")).isEqualTo(ManualChannel.BIZUM);
        assertThat(BillingRunService.channel("TRANSFER")).isEqualTo(ManualChannel.TRANSFER);
        assertThat(BillingRunService.channel("cheque")).isNull();
        assertThat(BillingRunService.channel(null)).isNull();
    }

    @Test void T_12_09_invoiceIssuedCarriesTheInvoiceItsMemberPeriodTotalMethodAndKind() {
        var invoice = invoice("invoice-1", "member-sepa", InvoiceStatus.COLLECTING, "run-1", "remittance-1");
        assertThat(BillingRunService.issuedPayload(invoice)).isEqualTo(Map.of("invoiceId", "invoice-1", "memberId", "member-sepa", "period", "2026-09",
                "total", new Money(4500, "EUR"), "paymentMethodType", "SEPA_DD", "kind", "PERIODIC"));
    }

    // --- rollback -----------------------------------------------------------------------------------------------------------

    @Test void T_12_21_rollingBackAnUnknownRunIsNotFound() {
        assertThatThrownBy(() -> service.rollback("run-unknown", "fictional reason", BillingRunService.CONFIRMATION))
                .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class).hasMessage("NOT_FOUND");
    }

    /**
     * Only states the code writes: each run invoice has its first attempt in the run's remittance (BillingRunService:112), and
     * invoice-1 was then marked unpaid by hand (R-12-17: a second SEPA_XML document, same attempt, FAILED{BANK_RETURN},
     * InvoiceActions:90-93), which blocks no rollback. The remitted receipt was remitted once before by a run that was rolled
     * back (attempt 1 in remittance-0 and its FAILED{ROLLBACK} copy), then by this run as attempt 2 (BillingRunService:353-356):
     * a COLLECTING receipt has exactly one attempt in its remittance, and it is its latest.
     */
    @Test void T_12_13_theRollbackAttemptsCopyTheLastAttemptOfEachInvoiceAndOfEachReceiptInTheRemittance() {
        var run = new BillingRun("run-1", CLUB, PERIOD.toString(), BillingRunStatus.GENERATED, "simulation-1", List.of("invoice-1", "invoice-2"), null,
                COLLECTION.toString(), NOW, NOW, List.of(), List.of(), 1L, null, null, null, 0L, NOW, null);
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(remittances.forRun("run-1")).thenReturn(Optional.of(remittance("remittance-1", "run-1")));
        when(invoices.forRun("run-1")).thenReturn(List.of(invoice("invoice-1", "member-sepa", InvoiceStatus.FAILED, "run-1", "remittance-1"),
                invoice("invoice-2", "member-other", InvoiceStatus.COLLECTING, "run-1", "remittance-1")));
        var receipt = receipt("receipt-r1", "member-waiting", InvoiceStatus.COLLECTING, "remittance-1");
        when(invoices.includedIn("remittance-1")).thenReturn(List.of(receipt));
        var invoiceAttempts = List.of(
                attempt("a-1", "invoice-1", 1, "remittance-1", "MANDATE-1", "2026-0001", SIGNED, CollectionStatus.CREATED),
                attempt("a-2", "invoice-2", 1, "remittance-1", "MANDATE-2", "2026-0002", SIGNED_LATER, CollectionStatus.CREATED),
                new Collection("a-1-returned", CLUB, "invoice-1", CollectionProvider.SEPA_XML, new Money(4500, "EUR"), CollectionStatus.FAILED, null, "remittance-1", 1,
                        InvoiceActions.BANK_RETURN, "fictional return", List.of(), NOW, NOW, "MANDATE-1", "2026-0001", null, null, SIGNED));
        var receiptAttempts = List.of(
                attempt("b-1", "receipt-r1", 1, "remittance-0", "MANDATE-A", "2026-0900", SIGNED, CollectionStatus.CREATED),
                new Collection("b-1-rolled-back", CLUB, "receipt-r1", CollectionProvider.SEPA_XML, new Money(2500, "EUR"), CollectionStatus.FAILED, null, "remittance-0", 1,
                        BillingRunService.ROLLBACK, "fictional earlier reason", List.of(), NOW, NOW, "MANDATE-A", "2026-0900", null, null, SIGNED),
                attempt("b-2", "receipt-r1", 2, "remittance-1", "MANDATE-B", "2026-0900", SIGNED_LATER, CollectionStatus.CREATED));
        var touched = new java.util.ArrayList<>(invoiceAttempts); touched.addAll(receiptAttempts);
        when(collections.forInvoices(List.of("invoice-1", "invoice-2", "receipt-r1"))).thenReturn(touched);
        when(collections.forInvoices(List.of("invoice-1", "invoice-2"))).thenReturn(invoiceAttempts);
        when(collections.forInvoice("receipt-r1")).thenReturn(receiptAttempts);
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);
        when(remittances.rollBack("remittance-1")).thenReturn(true);
        when(runs.rollBack(anyString(), anyLong(), any(), any())).thenReturn(true);

        var outcome = service.rollback("run-1", "fictional reason", BillingRunService.CONFIRMATION);

        assertThat(outcome.cancelledInvoices()).isEqualTo(2);
        var inserted = ArgumentCaptor.forClass(Collection.class);
        verify(collections, times(3)).insert(inserted.capture());
        // Each invoice continues its own last attempt, never another invoice's.
        var firstAttempt = inserted.getAllValues().stream().filter(attempt -> attempt.invoiceId().equals("invoice-1")).findFirst().orElseThrow();
        assertThat(firstAttempt).extracting(Collection::status, Collection::failureCode, Collection::provider, Collection::attempt, Collection::mandateRef,
                Collection::endToEndId, Collection::mandateSignedAt)
                .containsExactly(CollectionStatus.FAILED, BillingRunService.ROLLBACK, CollectionProvider.SEPA_XML, 1, "MANDATE-1", "2026-0001", SIGNED);
        var secondAttempt = inserted.getAllValues().stream().filter(attempt -> attempt.invoiceId().equals("invoice-2")).findFirst().orElseThrow();
        assertThat(secondAttempt).extracting(Collection::attempt, Collection::mandateRef, Collection::endToEndId, Collection::mandateSignedAt)
                .containsExactly(1, "MANDATE-2", "2026-0002", SIGNED_LATER);
        // The receipt continues its attempt in this remittance, not the rolled-back one of remittance-0.
        var receiptAttempt = inserted.getAllValues().stream().filter(attempt -> attempt.invoiceId().equals("receipt-r1")).findFirst().orElseThrow();
        assertThat(receiptAttempt).extracting(Collection::status, Collection::remittanceId, Collection::attempt, Collection::mandateRef,
                Collection::endToEndId, Collection::mandateSignedAt)
                .containsExactly(CollectionStatus.FAILED, "remittance-1", 2, "MANDATE-B", "2026-0900", SIGNED_LATER);
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    private void generating(InvoicingService.MonthPlan plan, List<String> waitingInvoiceIds) {
        var simulation = new BillingSimulation("simulation-1", CLUB, PERIOD.toString(), NOW.minusSeconds(60), List.of(), List.of(), List.of(), null, null,
                List.of(), waitingInvoiceIds);
        when(simulations.findById("simulation-1")).thenReturn(Optional.of(simulation));
        when(invoicing.plan(PERIOD)).thenReturn(plan);
        when(numbers.reserve(any(), anyInt())).thenReturn(new InvoiceNumbers.Block("2026", "2026", 1L));
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);
        when(writer.write(any(), anyString(), anyList(), any()))
                .thenAnswer(call -> remittance(call.getArgument(1), call.<BillingRun>getArgument(0).id()));
        when(remittances.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(runs.insert(any())).thenAnswer(call -> call.getArgument(0));
    }

    static InvoicingService.MonthPlan plan(List<InvoicingRules.Draft> drafts, List<InvoicingRules.Incident> skipped, Map<String, BillingMember> members,
            List<InvoicingService.WaitingReceipt> waiting) {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        var config = new ClubConfig(club, Map.of("billing.sepa.collectionDayOfMonth", 1), Set.of(), null, Map.of());
        var settings = new InvoicingRules.Settings("EUR", 1, InvoicingRules.CashInvoicing.MONTHLY, 6, true, false, false, false,
                Set.of(PaymentMethodType.SEPA_DD, PaymentMethodType.MANUAL));
        var context = new InvoicingService.Context(config, settings, ISSUE, Locale.forLanguageTag("ca"));
        return new InvoicingService.MonthPlan(context, PERIOD, new InvoicingRules.Month(drafts, List.of(), skipped, Map.of()), members, Map.of(), waiting);
    }

    static BillingMember member(String id, String type, String channel) {
        var method = new BillingCensusAccess.PaymentMethod(type, "SEPA_DD".equals(type), "ES** **** 1234", "Laura Serra", null, "MANDATE-" + id, null,
                false, channel, "SEPA_DD".equals(type) ? SIGNED : null);
        return new BillingMember(id, 1, "Laura", "Serra", "Puig", "ACTIVE", "plan-monthly", COLLECTION, null, null, method, "00000000T", "ca", NOW);
    }

    static InvoicingRules.Line line(long amount, String forMemberId) {
        var total = new Money(amount, "EUR");
        return new InvoicingRules.Line(InvoiceLineOrigin.MONTHLY_FEE, PERIOD, "price-1", null, null, "Quota",
                new InvoiceAmounts.Amounts(total, BigDecimal.ZERO, new Money(0, "EUR"), total), forMemberId);
    }

    /** A manual SEPA_DD receipt waiting for the next remittance (R-12-19). */
    static Invoice receipt(String id, String memberId) { return receipt(id, memberId, InvoiceStatus.PENDING, null); }

    /** The same receipt as a run left it: COLLECTING in the run's remittance, or PENDING in none. */
    static Invoice receipt(String id, String memberId, InvoiceStatus status, String remittanceId) {
        var total = new Money(2500, "EUR");
        return new Invoice(id, CLUB, "2026", 900, "2026-0900", "2026-08-01", "2026-08", memberId, new Invoice.MemberSnapshot(2, "Eva Roca", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.ADJUSTMENT, null, null, "Ajust", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, null, null, "MANDATE-" + memberId, null, null),
                status, InvoiceKind.MANUAL, null, remittanceId, true, null, null, null, null, null, null, new Money(0, "EUR"), null, 0L, NOW, null,
                NOW, null);
    }

    static Invoice invoice(String id, String memberId, InvoiceStatus status, String runId, String remittanceId) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, CLUB, "2026", 1, "2026-0001", ISSUE.toString(), PERIOD.toString(), memberId, new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "ES** **** 1234", "Laura Serra", "MANDATE-1", null, null),
                status, runId == null ? InvoiceKind.MANUAL : InvoiceKind.PERIODIC, runId, remittanceId, false, null, null, null, null, null, null,
                new Money(0, "EUR"), null, 0L, NOW, null, NOW, null);
    }

    static Collection attempt(String id, String invoiceId, int number, String remittanceId, String mandateRef, String endToEndId, Instant signedAt,
            CollectionStatus status) {
        return new Collection(id, CLUB, invoiceId, CollectionProvider.SEPA_XML, new Money(4500, "EUR"), status, null, remittanceId, number, null, null,
                List.of(), NOW, null, mandateRef, endToEndId, null, null, signedAt);
    }

    static Remittance remittance(String id, String runId) {
        return new Remittance(id, CLUB, runId, PERIOD.toString(), "club-a-2026-09-1", NOW, COLLECTION.toString(), null, List.of(), 0,
                new Money(0, "EUR"), null, "remittances/" + id + ".xml", NOW, null, RemittanceStatus.GENERATED, null, null, 0L, NOW, null);
    }
}
