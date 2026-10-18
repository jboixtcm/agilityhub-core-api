package com.agilityhub.core.configuration;

import io.sentry.spring.boot4.SentryProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E11-T08 (E11-T07's report, «Found along the way» 3): every `sentry.*` key of the application's configuration files binds to a
 * Sentry option. `sentry.enable-logs` bound to nothing (`SentryOptions` has no such setter); `sentry.logs.enabled` is the real
 * switch. Round 2 (review #8): every `application*.yml|yaml|properties` on the classpath, each profile document on its own.
 */
class SentryPropertiesBindingTest {
    @Test void E11_T08_everySentryKeyOfTheApplicationYamlBindsAndLogsAreOff() throws Exception {
        var files = new java.util.ArrayList<org.springframework.core.io.Resource>();
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (String pattern : List.of("classpath*:application*.yml", "classpath*:application*.yaml", "classpath*:application*.properties")) {
            files.addAll(List.of(resolver.getResources(pattern)));
        }
        assertThat(files).extracting(org.springframework.core.io.Resource::getFilename).contains("application.yml");
        int sentryKeys = 0;
        for (var file : files) {
            var loader = file.getFilename().endsWith(".properties") ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
            for (var document : loader.load(file.getFilename(), file)) {
                var source = new MapConfigurationPropertySource();
                var properties = (EnumerablePropertySource<?>) document;
                for (String name : properties.getPropertyNames()) {
                    if (name.startsWith("sentry.")) { source.put(name, placeholderDefault(String.valueOf(properties.getProperty(name)))); sentryKeys++; }
                }
                // Fails with UnboundConfigurationPropertiesException on a key that no Sentry option takes.
                var bound = new Binder(source).bind("sentry", Bindable.ofInstance(new SentryProperties()),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).orElseGet(SentryProperties::new);
                assertThat(bound.getLogs().isEnabled()).as(file.getFilename() + " " + document.getName()).isFalse();
                assertThat(bound.isSendDefaultPii()).as(file.getFilename() + " " + document.getName()).isFalse();
            }
        }
        assertThat(sentryKeys).isGreaterThanOrEqualTo(5);
    }

    /** `${VAR:default}` → its default, `${VAR}` → empty: the value an unset variable gives. */
    private static String placeholderDefault(String value) {
        return value.replaceAll("\\$\\{[^:}]+:([^}]*)}", "$1").replaceAll("\\$\\{[^}]+}", "");
    }

    @Test void E11_T08_theLogsSwitchIsTheKeyThatBinds() {
        var bound = new Binder(new MapConfigurationPropertySource(Map.of("sentry.logs.enabled", "true")))
                .bind("sentry", Bindable.ofInstance(new SentryProperties()), new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();
        assertThat(bound.getLogs().isEnabled()).isTrue();
    }
}
