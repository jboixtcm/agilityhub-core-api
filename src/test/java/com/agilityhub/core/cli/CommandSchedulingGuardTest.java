package com.agilityhub.core.cli;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import static org.assertj.core.api.Assertions.*;

class CommandSchedulingGuardTest {
    @Test void T_18_09_commandGuardOverridesDeploymentAndCommandLineSchedulingWithoutChangingOtherOptions() {
        try (var context = new GenericApplicationContext()) {
            var environment = context.getEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("deploymentEnvironment",
                    Map.of("shared.scheduling.enabled", true, "core.command", "migration:welcome")));
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--shared.scheduling.enabled=true"));
            assertThat(environment.getProperty("shared.scheduling.enabled", Boolean.class)).isTrue();
            new CommandSchedulingGuard().initialize(context);
            assertThat(environment.getProperty("shared.scheduling.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("core.command")).isEqualTo("migration:welcome");
        }
    }

    @Test void T_18_09_commandGuardDisablesSchedulingEvenWithoutADeploymentSetting() {
        try (var context = new GenericApplicationContext()) {
            new CommandSchedulingGuard().initialize(context);
            assertThat(context.getEnvironment().getProperty("shared.scheduling.enabled", Boolean.class)).isFalse();
        }
    }
}
