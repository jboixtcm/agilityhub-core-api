package com.agilityhub.core.cli;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** CLI commands must never start scheduled jobs, including when a release environment enables them for the web server. */
public final class CommandSchedulingGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("coreCommandSchedulingGuard",
                Map.of("shared.scheduling.enabled", false)));
    }
}
