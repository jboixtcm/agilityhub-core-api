package com.agilityhub.core.payments.application;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E11-T06 PIT survivor of {@link BillingTexts} (S12 R-12-30): a catalog item without a name has no name, not an empty one. */
class BillingTextsSurvivorsTest {
    @Test void E11_T06_aMissingCatalogNameStaysNull() {
        assertThat(BillingTexts.text(null, Locale.forLanguageTag("ca"))).isNull();
    }
}
