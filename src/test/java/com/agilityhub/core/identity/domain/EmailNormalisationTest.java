package com.agilityhub.core.identity.domain;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmailNormalisationTest {
    @Test void T_01_01_trimLowercaseAndNfcAreIndependentOfMachineLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(Email.normalize("  IRE\u0300NE@EXAMPLE.TEST ")).isEqualTo("irène@example.test");
            assertThat(Email.normalize(Email.normalize("  IRE\u0300NE@EXAMPLE.TEST "))).isEqualTo("irène@example.test");
        } finally { Locale.setDefault(original); }
    }
}
