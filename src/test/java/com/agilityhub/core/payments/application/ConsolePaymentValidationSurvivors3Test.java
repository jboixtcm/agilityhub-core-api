package com.agilityhub.core.payments.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT session-10 survivors of {@link ConsolePaymentValidation} (S17 R-17-05, T-17-05): the IBAN check (ISO 13616
 * mod 97) counts every letter as two digits, `A` (= 10) included, so valid IBANs with an `A` in the country or bank code pass.
 * The IBANs are the published ISO/EPC examples, not real accounts.
 */
class ConsolePaymentValidationSurvivors3Test {
    @Test void T_17_05_anIbanWithTheLetterAIsCheckedWithBothOfItsDigits() {
        assertThat(ConsolePaymentValidation.iban("AT61 1904 3002 3457 3201")).isTrue();
        assertThat(ConsolePaymentValidation.iban("NL91 ABNA 0417 1643 00")).isTrue();
    }
}
