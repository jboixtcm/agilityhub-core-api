package com.agilityhub.core.platform.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E11-T15 step 8 (the audit of 21-10, F8; S12 R-12-12 `CdtrSchmeId`, S17 R-17-05): a stored creditor identifier is checked again
 * at run time — shape and ISO 7064 mod 97-10 check digits — so wrong digits are `422 SEPA_NOT_CONFIGURED` (and the simulation's
 * `PROVIDER_DISABLED`) instead of a file the bank refuses.
 */
class BillingProviderSettingsTest {
    static final String IBAN = "ES0000000000000000009876";

    @Test void R_12_12_aCreditorIdentifierWithWrongCheckDigitsIsNotConfigured() {
        assertThat(new BillingProviderSettings.SepaCreditor("Club", "ES00ZZZB00000000", IBAN, null, null).configured()).isFalse();
        assertThat(new BillingProviderSettings.SepaCreditor("Club", "ES00ZZZG12345678", IBAN, null, "001").configured()).isFalse();
        assertThat(new BillingProviderSettings.SepaCreditor("Club", "NOT AN IDENTIFIER", IBAN, null, null).configured()).isFalse();
        assertThat(new BillingProviderSettings.SepaCreditor("Club", "ES81ZZZB00000000", IBAN, null, null).configured()).isTrue();
        assertThat(new BillingProviderSettings.SepaCreditor("Club", "es26 000 g12345678", IBAN, null, "001").configured()).isTrue();
        assertThat(new BillingProviderSettings.SepaCreditor("Club", "ES64ZZZ999999999", IBAN, null, null).configured()).isTrue();
    }
}
