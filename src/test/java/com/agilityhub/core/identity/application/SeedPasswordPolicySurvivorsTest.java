package com.agilityhub.core.identity.application;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link SeedPasswordPolicy} (club-as-code seeds, `--allow-seed-passwords`): staging/prod accept only the
 * `${SEED_PASSWORD}` reference with the flag, and a local literal password needs no `SEED_PASSWORD`. Fictional passwords.
 */
class SeedPasswordPolicySurvivorsTest {
    static final String REFERENCE = "${SEED_PASSWORD}";

    static MockEnvironment profiles(String... active) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test void E11_T06_productionResolvesTheSeedPasswordReferenceWithTheFlag() {
        var policy = new SeedPasswordPolicy(profiles("prod"), "Seed-Example-Pass-1");

        assertThat(policy.resolve(REFERENCE, true)).isEqualTo("Seed-Example-Pass-1");
    }

    @Test void E11_T06_productionRefusesTheSeedPasswordReferenceWithoutTheFlag() {
        var policy = new SeedPasswordPolicy(profiles("prod"), "Seed-Example-Pass-1");

        assertThatThrownBy(() -> policy.resolve(REFERENCE, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--allow-seed-passwords");
    }

    @Test void E11_T06_productionRefusesALiteralSeedPasswordEvenWithTheFlag() {
        var policy = new SeedPasswordPolicy(profiles("prod"), "Seed-Example-Pass-1");

        assertThatThrownBy(() -> policy.resolve("Literal-Example-1", true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--allow-seed-passwords");
    }

    @Test void E11_T06_aLocalLiteralSeedPasswordNeedsNoSeedPasswordVariable() {
        var policy = new SeedPasswordPolicy(profiles("local"), "");

        assertThat(policy.resolve("Literal-Example-1", false)).isEqualTo("Literal-Example-1");
    }

    @Test void E11_T06_aLocalSeedPasswordReferenceWithoutTheVariableIsRefused() {
        var policy = new SeedPasswordPolicy(profiles("local"), "");

        assertThatThrownBy(() -> policy.resolve(REFERENCE, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Set SEED_PASSWORD");
    }
}
