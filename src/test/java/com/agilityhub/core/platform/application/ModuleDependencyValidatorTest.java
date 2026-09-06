package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleDependencyValidatorTest {
    private final ModuleDependencyValidator validator = new ModuleDependencyValidator();

    @ParameterizedTest
    @EnumSource(Module.class)
    void T_02_09_enablingChecksEveryCatalogDependency(Module module) {
        Set<Module> current = EnumSet.noneOf(Module.class);
        if (module.dependsOn().isEmpty()) {
            assertThatCode(() -> validator.validateChange(current, module, true)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> validator.validateChange(current, module, true))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.code()).isEqualTo(ErrorCode.MODULE_DEPENDENCY);
                        assertThat(error.code().httpStatus()).isEqualTo(422);
                        assertThat(error.details()).isEqualTo(Map.of("missing", module.dependsOn().stream().map(Enum::name).toList()));
                    });
            current.addAll(module.dependsOn());
            assertThatCode(() -> validator.validateChange(current, module, true)).doesNotThrowAnyException();
        }
        assertThat(current).doesNotContain(module);
    }

    @Test
    void T_02_09_disablingBillingListsEnabledDependentsWithoutChangingConfiguration() {
        Set<Module> current = EnumSet.of(Module.BILLING, Module.PACKS, Module.SINGLE_CLASS, Module.INACTIVITY);
        assertThatThrownBy(() -> validator.validateChange(current, Module.BILLING, false))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.MODULE_DEPENDENCY);
                    assertThat(error.details()).isEqualTo(Map.of("dependents", List.of(Module.PACKS.name(), Module.SINGLE_CLASS.name())));
                });
        assertThat(current).containsExactly(Module.BILLING, Module.PACKS, Module.SINGLE_CLASS, Module.INACTIVITY);
        assertThatThrownBy(() -> validator.validateChange(Set.of(Module.BILLING, Module.PACKS), Module.BILLING, false))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.details()).containsEntry("dependents", List.of(Module.PACKS.name())));
    }

    @Test
    void T_02_09_disablingStatsRequiresRemovingSocialLeague() {
        assertThatThrownBy(() -> validator.validateChange(Set.of(Module.STATS, Module.SOCIAL_LEAGUE), Module.STATS, false))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.MODULE_DEPENDENCY);
                    assertThat(error.details()).containsEntry("dependents", List.of(Module.SOCIAL_LEAGUE.name()));
                });
        assertThatCode(() -> validator.validateChange(Set.of(Module.STATS, Module.SOCIAL_LEAGUE), Module.SOCIAL_LEAGUE, false))
                .doesNotThrowAnyException();
    }

    @Test
    void T_02_09_fullConfigurationListsAllMissingDependenciesOnce() {
        assertThatThrownBy(() -> validator.validate(Set.of(Module.PACKS, Module.SINGLE_CLASS, Module.SOCIAL_LEAGUE)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.MODULE_DEPENDENCY);
                    assertThat(error.details()).isEqualTo(Map.of("missing", List.of(Module.BILLING.name(), Module.STATS.name())));
                });
    }

    @Test
    void T_02_09_optionalInactivityFeeAndRepeatedTogglesAreAllowed() {
        assertThatCode(() -> {
            validator.validate(Set.of());
            validator.validate(Set.of(Module.INACTIVITY));
            validator.validateChange(Set.of(Module.BILLING, Module.INACTIVITY), Module.BILLING, false);
            validator.validateChange(Set.of(Module.PUSH), Module.PUSH, true);
            validator.validateChange(Set.of(), Module.PUSH, false);
        }).doesNotThrowAnyException();
    }
}
