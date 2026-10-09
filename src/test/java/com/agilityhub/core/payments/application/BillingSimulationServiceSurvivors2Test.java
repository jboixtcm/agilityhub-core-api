package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingLockRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingSimulationRepository;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT re-run survivor of {@link BillingSimulationService} (S12 R-12-07; T-12-09): the inactivity-fee KPI counts the
 * `INACTIVITY_FEE` lines, never the others — with one inactivity line and two others, so the count and its complement differ.
 * The fixture helpers are {@link BillingSimulationServiceSurvivorsTest}'s; fictional members only.
 */
class BillingSimulationServiceSurvivors2Test {
    final BillingSimulationService service = new BillingSimulationService(mock(InvoicingService.class), mock(BillingSimulationRepository.class),
            mock(BillingLockRepository.class), mock(BillingEvents.class), mock(BillingTransactions.class), mock(BillingProviderSettings.class),
            Clock.fixed(BillingSimulationServiceSurvivorsTest.NOW, ZoneOffset.UTC));

    @Test void T_12_09_theInactivityKpiCountsTheInactivityLinesAndNotTheOthers() {
        var draft = new InvoicingRules.Draft("member-cash", List.of(BillingSimulationServiceSurvivorsTest.line(InvoiceLineOrigin.MONTHLY_FEE, 4500),
                BillingSimulationServiceSurvivorsTest.line(InvoiceLineOrigin.INACTIVITY_FEE, 1000),
                BillingSimulationServiceSurvivorsTest.line(InvoiceLineOrigin.MAINTENANCE_FEE, 1500)), List.of());

        var simulation = service.build(BillingSimulationServiceSurvivorsTest.plan(List.of(draft), List.of(), List.of()));

        assertThat(simulation.kpis().inactivityFees().count()).isEqualTo(1);
    }
}
