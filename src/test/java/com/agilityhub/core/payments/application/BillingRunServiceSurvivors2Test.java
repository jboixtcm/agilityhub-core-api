package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.application.ports.RemittanceWriterPort;
import com.agilityhub.core.payments.domain.BillingIncidentCode;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingRunRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingSimulationRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.BillingSimulation;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.InvoiceCounters;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT re-run survivors of {@link BillingRunService} (S12 R-12-07, R-12-09; T-12-05, T-12-09): a simulation is stale when
 * a member it listed as an incident or as a cash member changed after it, and an invoice's lines are numbered from 1. The
 * fixture helpers are {@link BillingRunServiceSurvivorsTest}'s; collaborators are mocks; fictional members only.
 */
class BillingRunServiceSurvivors2Test {
    static final Instant NOW = BillingRunServiceSurvivorsTest.NOW;

    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingRunService service = new BillingRunService(mock(InvoicingService.class), mock(BillingSimulationService.class), mock(BillingRunRepository.class),
            mock(BillingSimulationRepository.class), mock(InvoiceRepository.class), mock(CollectionRepository.class), mock(RemittanceRepository.class),
            mock(PendingChargeRepository.class), mock(RemittanceWriterPort.class), mock(InvoiceCounters.class), mock(InvoiceNumbers.class), census,
            mock(BillingCatalogAccess.class), mock(BillingEvents.class), mock(AuditWriter.class), mock(BillingTransactions.class), Clock.fixed(NOW, ZoneOffset.UTC));

    /** No drafts, no charges, no waiting receipts: only the members' last change can make the simulation stale. */
    static InvoicingService.MonthPlan emptyPlan() {
        return BillingRunServiceSurvivorsTest.plan(List.of(), List.of(), Map.of(), List.of());
    }

    @Test void T_12_09_aChangeOfAMemberListedAsAnIncidentAfterTheSimulationMakesItStale() {
        var simulation = new BillingSimulation("simulation-1", BillingRunServiceSurvivorsTest.CLUB, BillingRunServiceSurvivorsTest.PERIOD.toString(), NOW,
                List.of(new BillingSimulation.Incident("member-incident", "Pau Roca", BillingIncidentCode.NO_BANK_ACCOUNT)), List.of(), List.of(), null, null, null, null);
        when(census.lastChange(Set.of("member-incident"))).thenReturn(Optional.of(NOW.plusSeconds(60)));

        assertThat(service.stale(simulation, emptyPlan())).isTrue();
        verify(census).lastChange(Set.of("member-incident"));
    }

    @Test void T_12_09_aChangeOfAMemberListedAsCashAfterTheSimulationMakesItStale() {
        var simulation = new BillingSimulation("simulation-1", BillingRunServiceSurvivorsTest.CLUB, BillingRunServiceSurvivorsTest.PERIOD.toString(), NOW,
                List.of(), List.of(new BillingSimulation.CashMember("member-cash", "Eva Roca", null)), List.of(), null, null, null, null);
        when(census.lastChange(Set.of("member-cash"))).thenReturn(Optional.of(NOW.plusSeconds(60)));

        assertThat(service.stale(simulation, emptyPlan())).isTrue();
        verify(census).lastChange(Set.of("member-cash"));
    }

    @Test void T_12_05_anInvoicesLinesAreNumberedFromOneInTheirOrder() {
        var context = emptyPlan().context();
        var member = BillingRunServiceSurvivorsTest.member("member-sepa", "SEPA_DD", null);
        var lines = List.of(BillingRunServiceSurvivorsTest.line(4500, null), BillingRunServiceSurvivorsTest.line(3000, null));

        var invoice = BillingRunService.invoice("invoice-1", context, "2026", 1L, BillingRunServiceSurvivorsTest.ISSUE, BillingRunServiceSurvivorsTest.PERIOD,
                member, lines, PaymentMethodType.SEPA_DD, InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC, "run-1", "remittance-1", false, null, NOW, null);

        assertThat(invoice.lines()).extracting(Invoice.Line::lineNo).containsExactly(1, 2);
        assertThat(invoice.lines()).extracting(Invoice.Line::total).containsExactly(new Money(4500, "EUR"), new Money(3000, "EUR"));
    }
}
