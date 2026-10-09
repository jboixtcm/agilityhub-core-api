package com.agilityhub.core.payments.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E11-T06 PIT survivors of {@link SepaText} (S12 R-12-12, T-12-06). */
class SepaTextSurvivorsTest {

    /** The last letter and digit of each range ('Z', 'z', '9') are in the SEPA character set. */
    @Test void T_12_06_theUpperEndsOfTheLetterAndDigitRangesAreKept() {
        assertThat(SepaText.allowed('Z')).isTrue();
        assertThat(SepaText.allowed('z')).isTrue();
        assertThat(SepaText.allowed('9')).isTrue();
        assertThat(SepaText.of("Zoe Ruiz 2029", SepaText.NAME)).isEqualTo("Zoe Ruiz 2029");
    }
}
