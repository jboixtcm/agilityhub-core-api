package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.application.SeedPasswordPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SeedProfileTest {
    @ParameterizedTest @ValueSource(strings = {"local", "test", "prod", "staging", "local,prod", "test,staging", "unknown"})
    void T_01_02_seedPasswordsRequireAllowedProfileAndExplicitDeploymentOverride(String profile) {
        var environment = new MockEnvironment(); environment.setActiveProfiles(profile.split(","));
        var policy = new SeedPasswordPolicy(environment, "Fictional-seed-password");
        boolean local = profile.equals("local") || profile.equals("test");
        boolean deployed = profile.contains("staging") || profile.contains("prod");
        assertThat(policy.resolve(null, false)).isNull();
        for (boolean override : new boolean[]{false, true}) {
            if (local || deployed && override) {
                assertThat(policy.resolve("${SEED_PASSWORD}", override)).isEqualTo("Fictional-seed-password");
            } else {
                assertThatThrownBy(() -> policy.resolve("${SEED_PASSWORD}", override)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("--allow-seed-passwords").hasMessageNotContaining("Fictional-seed-password");
            }
            if (local) { assertThat(policy.resolve("Temporary-test-password", override)).isEqualTo("Temporary-test-password"); }
            else { assertThatThrownBy(() -> policy.resolve("Temporary-test-password", override)).isInstanceOf(IllegalArgumentException.class); }
        }
    }
    @Test void T_01_02_seedPasswordHasNoFallbackAndDefaultLocalProfileWorks() {
        var environment = new MockEnvironment(); environment.setDefaultProfiles("local");
        var policy = new SeedPasswordPolicy(environment, "");
        assertThatThrownBy(() -> policy.resolve("${SEED_PASSWORD}", false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Set SEED_PASSWORD");
        assertThat(policy.resolve(null, true)).isNull();
    }
}
