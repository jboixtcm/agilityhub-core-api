package com.agilityhub.core.payments.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E11-T06 PIT survivors of {@link PendingChargeRules} (S12 R-12-25, T-12-08). */
class PendingChargeRulesSurvivorsTest {

    /** A first mark (no previous state) charges; moving between two charged marks never charges again. */
    @Test void T_12_08_aFirstMarkWithoutAPreviousStateChargesAndAChargedToChargedChangeDoesNot() {
        assertThat(PendingChargeRules.onAttendance("PRESENT", null, PendingChargeRules.Existing.NONE)).isEqualTo(PendingChargeRules.Action.CHARGE);
        assertThat(PendingChargeRules.onAttendance("NO_SHOW", null, PendingChargeRules.Existing.VOIDED)).isEqualTo(PendingChargeRules.Action.REINSTATE);
        assertThat(PendingChargeRules.onAttendance("NO_SHOW", "PRESENT", PendingChargeRules.Existing.NONE)).isEqualTo(PendingChargeRules.Action.NONE);
    }
}
