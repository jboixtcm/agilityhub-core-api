package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.Money;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link PaymentAudits} (S12 R-12-13/21): the methods' results are the audit entries' `details`
 * (`details = "#result"`), so they must carry the run's counters and the recorded amount.
 */
class PaymentAuditsSurvivorsTest {
    final PaymentAudits audits = new PaymentAudits();

    @Test void T_12_15_theCardChargesAuditDetailsCarryTheRunAndItsCounters() {
        assertThat(audits.started("run-1", 3, 1)).isEqualTo(Map.of("runId", "run-1", "submitted", 3, "skipped", 1));
    }

    @Test void E11_T06_theRecordedUpfrontPaymentAuditDetailsCarryTheAmountPaid() {
        var amount = new Money(4500, "EUR");
        assertThat(audits.recorded("upfront-1", "member-1", amount)).isEqualTo(Map.of("amountPaid", amount));
    }
}
