package com.agilityhub.core.payments.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E11-T06 PIT survivors of {@link SepaDirectDebits} (S12 R-12-12, T-12-06). */
class SepaDirectDebitsSurvivorsTest {

    /** The shortest identifier that still has a business code (8 characters) takes the suffix; a shorter one stays as configured. */
    @Test void T_12_06_anEightCharacterCreditorIdentifierStillTakesTheSuffix() {
        assertThat(SepaDirectDebits.creditorIdentifier("ES12ZZZB", "001")).isEqualTo("ES12001B");
        assertThat(SepaDirectDebits.creditorIdentifier("ES12ZZZ", "001")).isEqualTo("ES12ZZZ");
    }
}
