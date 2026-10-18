package com.agilityhub.core.configuration;

import io.sentry.spring.boot4.SentryProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.EnumerablePropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E11-T08 (E11-T07's report, «Found along the way» 3): every `sentry.*` key of `application.yml` binds to a Sentry option.
 * `sentry.enable-logs` bound to nothing (`SentryOptions` has no such setter); `sentry.logs.enabled` is the real switch.
 */
class SentryPropertiesBindingTest {
    @Test void E11_T08_everySentryKeyOfTheApplicationYamlBindsAndLogsAreOff() throws Exception {
        var source = new MapConfigurationPropertySource();
        for (var document : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            var properties = (EnumerablePropertySource<?>) document;
            for (String name : properties.getPropertyNames()) {
                if (name.startsWith("sentry.")) { source.put(name, String.valueOf(properties.getProperty(name)).replace("${SENTRY_DSN:}", "")); }
            }
        }
        // Fails with UnboundConfigurationPropertiesException on a key that no Sentry option takes.
        var bound = new Binder(source).bind("sentry", Bindable.ofInstance(new SentryProperties()),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();

        assertThat(bound.getLogs().isEnabled()).isFalse();
        assertThat(bound.isSendDefaultPii()).isFalse();
    }

    @Test void E11_T08_theLogsSwitchIsTheKeyThatBinds() {
        var bound = new Binder(new MapConfigurationPropertySource(Map.of("sentry.logs.enabled", "true")))
                .bind("sentry", Bindable.ofInstance(new SentryProperties()), new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();
        assertThat(bound.getLogs().isEnabled()).isTrue();
    }
}
