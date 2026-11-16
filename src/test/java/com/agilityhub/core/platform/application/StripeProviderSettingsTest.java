package com.agilityhub.core.platform.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class StripeProviderSettingsTest {
    @ParameterizedTest
    @CsvSource({
        "live,sk_live_fictional,true", "live,rk_live_fictional,true",
        "test,sk_test_fictional,true", "test,rk_test_fictional,true",
        "live,sk_test_fictional,false", "live,rk_test_fictional,false",
        "test,sk_live_fictional,false", "test,rk_live_fictional,false",
        "live,pk_live_fictional,false", "live,sk_org_fictional,false",
        "live,whsec_fictional,false", "live,,false", ",rk_live_fictional,false",
        "other,rk_other_fictional,false", "test,unrecognised,false"
    })
    void secretAndRestrictedKeysAreAcceptedOnlyForTheirOwnMode(String mode, String key, boolean accepted) {
        assertThat(StripeProviderSettings.acceptsSecretKey(mode, key)).isEqualTo(accepted);
    }
}
