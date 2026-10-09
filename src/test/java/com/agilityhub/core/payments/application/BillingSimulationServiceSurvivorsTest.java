package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.BillingIncidentCode;
import com.agilityhub.core.payments.domain.InvoiceAmounts;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingLockRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingSimulationRepository;
import com.agilityhub.core.payments.persistence.BillingSimulation;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BillingSimulationService} (S12 R-12-07, R-12-19; T-12-09): the inactivity-fee KPI counts only
 * `INACTIVITY_FEE` lines, a waiting receipt alone opens the SEPA totals, and `RemittanceSimulated` names each incident.
 */
class BillingSimulationServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final YearMonth PERIOD = YearMonth.of(2026, 9);
    static final LocalDate ISSUE = LocalDate.of(2026, 8, 25);
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");

    final InvoicingService invoicing = mock(InvoicingService.class);
    final BillingSimulationRepository simulations = mock(BillingSimulationRepository.class);
    final BillingLockRepository locks = mock(BillingLockRepository.class);
    final BillingEvents events = mock(BillingEvents.class);
    final BillingTransactions transactions = mock(BillingTransactions.class);
    final BillingSimulationService service = new BillingSimulationService(invoicing, simulations, locks, events, transactions,
            mock(BillingProviderSettings.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void T_12_09_theInactivityKpiCountsOnlyInactivityLinesAndAWaitingReceiptAloneOpensTheSepaTotals() {
        var draft = new InvoicingRules.Draft("member-cash", List.of(line(InvoiceLineOrigin.MONTHLY_FEE, 4500), line(InvoiceLineOrigin.INACTIVITY_FEE, 1000)), List.of());
        var waiting = new InvoicingService.WaitingReceipt(receipt("receipt-r1", "member-waiting"), "Eva Roca", null);
        var simulation = service.build(plan(List.of(draft), List.of(), List.of(waiting)));

        assertThat(simulation.kpis().inactivityFees().count()).isEqualTo(1);
        assertThat(simulation.kpis().count()).isEqualTo(2);
        assertThat(simulation.kpis().byProvider().sepaXml()).isEqualTo(new BillingSimulation.Totals(1, new Money(2500, "EUR")));
        assertThat(simulation.kpis().byProvider().manual()).isEqualTo(new BillingSimulation.Totals(1, new Money(5500, "EUR")));
        assertThat(simulation.waitingInvoiceIds()).containsExactly("receipt-r1");
    }

    @Test void T_12_09_remittanceSimulatedNamesEachIncidentWithItsMemberAndCode() {
        var plan = plan(List.of(), List.of(new InvoicingRules.Incident("member-x", BillingIncidentCode.NO_PLAN)), List.of());
        when(invoicing.context()).thenReturn(plan.context());
        when(invoicing.plan(PERIOD)).thenReturn(plan);
        when(locks.acquire(anyString(), any())).thenReturn(true);
        when(transactions.run(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        when(simulations.store(any())).thenAnswer(call -> call.getArgument(0));

        service.simulate(PERIOD);

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(BillingEvent.Kind.RemittanceSimulated), anyString(), payload.capture());
        assertThat(payload.getValue().get("incidents")).isEqualTo(List.of(Map.of("memberId", "member-x", "code", "NO_PLAN")));
    }

    static InvoicingService.MonthPlan plan(List<InvoicingRules.Draft> drafts, List<InvoicingRules.Incident> skipped, List<InvoicingService.WaitingReceipt> waiting) {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        var fee = Map.<String, Object>of("amountMinor", 1000, "currency", "EUR");
        var config = new ClubConfig(club, Map.of("billing.sepa.collectionDayOfMonth", 1, "billing.inactivityFeeFirstMonth", fee,
                "billing.inactivityFeeFollowingMonths", fee), Set.of(), null, Map.of());
        // SEPA_DD is not enabled, so the creditor check (E8-T03) does not apply: the waiting receipt is remitted.
        var settings = new InvoicingRules.Settings("EUR", 1, InvoicingRules.CashInvoicing.MONTHLY, 6, true, false, true, false, Set.of(PaymentMethodType.MANUAL));
        var context = new InvoicingService.Context(config, settings, ISSUE, Locale.forLanguageTag("ca"));
        var members = Map.of("member-cash", member("member-cash"));
        return new InvoicingService.MonthPlan(context, PERIOD, new InvoicingRules.Month(drafts, List.of(), skipped, Map.of()), members, Map.of(), waiting);
    }

    static BillingMember member(String id) {
        var method = new BillingCensusAccess.PaymentMethod("MANUAL", false, null, "Laura Serra", null, null, null, false, "cash", null);
        return new BillingMember(id, 1, "Laura", "Serra", "Puig", "ACTIVE", "plan-monthly", PERIOD.atDay(1), null, null, method, "00000000T", "ca", NOW);
    }

    static InvoicingRules.Line line(InvoiceLineOrigin origin, long amount) {
        var total = new Money(amount, "EUR");
        return new InvoicingRules.Line(origin, PERIOD, "price-1", null, null, "Quota",
                new InvoiceAmounts.Amounts(total, BigDecimal.ZERO, new Money(0, "EUR"), total), null);
    }

    static Invoice receipt(String id, String memberId) {
        var total = new Money(2500, "EUR");
        return new Invoice(id, CLUB, "2026", 900, "2026-0900", "2026-08-01", "2026-08", memberId, new Invoice.MemberSnapshot(2, "Eva Roca", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.ADJUSTMENT, null, null, "Ajust", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, null, null, "MANDATE-" + memberId, null, null),
                InvoiceStatus.PENDING, InvoiceKind.MANUAL, null, null, true, null, null, null, null, null, null, new Money(0, "EUR"), null, 0L, NOW, null,
                NOW, null);
    }
}
