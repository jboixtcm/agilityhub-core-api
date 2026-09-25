package com.agilityhub.core.platform.application;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E3-T14: one rule for a stored provider's flags, and one provider ↔ method mapping (S04 R-04-10). */
class PaymentProviderFlagsTest {
    @Test void R_04_10_T_04_14_onlyEnabledTrueEnablesAndConfiguredNeverDependsOnTheSwitch() {
        assertThat(PaymentProviderFlags.enabled(Map.of("enabled", true))).isTrue();
        assertThat(PaymentProviderFlags.enabled(Map.of())).isFalse();
        assertThat(PaymentProviderFlags.enabled(Map.of("enabled", "true"))).isFalse();
        assertThat(PaymentProviderFlags.enabled(null)).isFalse();
        assertThat(PaymentProviderFlags.configured(Map.of("enabled", true))).isFalse();
        assertThat(PaymentProviderFlags.configured(Map.of("enabled", false, "creditorName", "Fictional Creditor"))).isTrue();
        var withNull = new HashMap<String, Object>(); withNull.put("creditorName", null);
        assertThat(PaymentProviderFlags.configured(withNull)).isFalse();
    }
    @Test void R_04_10_T_04_14_eachProviderCollectsWithOneMethodAndBack() {
        assertThat(PaymentProviderFlags.method("SEPA_XML")).isEqualTo("SEPA_DD");
        assertThat(PaymentProviderFlags.method("STRIPE")).isEqualTo("CARD");
        assertThat(PaymentProviderFlags.method("MANUAL")).isEqualTo("MANUAL");
        assertThat(PaymentProviderFlags.provider("SEPA_DD")).isEqualTo("SEPA_XML");
        assertThat(PaymentProviderFlags.provider("CARD")).isEqualTo("STRIPE");
        assertThat(PaymentProviderFlags.provider("MANUAL")).isEqualTo("MANUAL");
        assertThat(PaymentProviderFlags.method("PAYPAL")).isNull();
        assertThat(PaymentProviderFlags.method(null)).isNull();
        assertThat(PaymentProviderFlags.provider("SEPA_XML")).isNull();
        assertThat(PaymentProviderFlags.provider(null)).isNull();
    }
}
