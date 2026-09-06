package com.agilityhub.core.shared.application;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import static org.assertj.core.api.Assertions.*;

class IcuFormatTest {
    private final IcuMessageSource source = new IcuMessageSource("classpath:icu/messages_*.properties");
    private final Locale ca = Locale.forLanguageTag("ca");
    IcuFormatTest() throws java.io.IOException { }

    @Test void E0_T08_namedPluralAndGenderSelectRenderInCatalan() {
        assertThat(source.format("dogs", Map.of("count", 0), ca)).isEqualTo("0 gossos");
        assertThat(source.format("dogs", Map.of("count", 1), ca)).isEqualTo("1 gos");
        assertThat(source.format("dogs", Map.of("count", 2), ca)).isEqualTo("2 gossos");
        assertThat(source.format("welcome", Map.of("gender", "female"), ca)).isEqualTo("Benvinguda");
        assertThat(source.format("welcome", Map.of("gender", "male"), ca)).isEqualTo("Benvingut");
        assertThat(source.format("welcome", Map.of("gender", "other"), ca)).isEqualTo("Benvingut");
    }
    @Test void E0_T08_standardMessageSourceSupportsPositionalArgumentsAndDefaults() {
        assertThat(source.getMessage("positional", new Object[]{"Alex"}, ca)).isEqualTo("Hola, Alex!");
        assertThat(source.getMessage("missing", new Object[]{"Alex"}, "Hola, {0}!", ca)).isEqualTo("Hola, Alex!");
        assertThat(source.getMessage("missing", null, null, ca)).isNull();
        assertThat(source.getMessage(new DefaultMessageSourceResolvable(new String[]{"missing", "positional"},
                new Object[]{"Alex"}), ca)).isEqualTo("Hola, Alex!");
        assertThat(source.getMessage(new DefaultMessageSourceResolvable(new String[]{"missing"},
                new Object[]{"Alex"}, "Hola, {0}!"), ca)).isEqualTo("Hola, Alex!");
        assertThat(source.getMessage(new DefaultMessageSourceResolvable(null, null, "Hola!"), ca)).isEqualTo("Hola!");
        assertThatThrownBy(() -> source.getMessage("missing", null, ca)).isInstanceOf(NoSuchMessageException.class);
        assertThatThrownBy(() -> source.getMessage(new DefaultMessageSourceResolvable("missing"), ca))
                .isInstanceOf(NoSuchMessageException.class);
        assertThatThrownBy(() -> new IcuMessageSource("classpath:icu/messages_es.properties"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Catalan");
    }
    @Test void E0_T08_fallbackDoesNotDependOnTheJvmLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPANESE);
            assertThat(source.getMessage("onlyCatalan", null, Locale.forLanguageTag("es"))).isEqualTo("Només en català");
            assertThat(source.getMessage("onlyCatalan", null, Locale.FRENCH)).isEqualTo("Només en català");
            assertThat(source.format("dogs", Map.of("count", 2), Locale.forLanguageTag("es-MX"))).isEqualTo("2 perros");
            assertThat(source.supports(Locale.forLanguageTag("es-MX"))).isTrue();
            assertThat(source.supports(Locale.FRENCH)).isFalse();
        } finally { Locale.setDefault(previous); }
    }
    @Test void E0_T08_formattingIsSafeForConcurrentRecipients() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int count = 0; count < 100; count++) {
                int value = count;
                results.add(executor.submit(() -> source.format("dogs", Map.of("count", value), ca)));
            }
            for (int count = 0; count < results.size(); count++) {
                assertThat(results.get(count).get()).isEqualTo(count == 1 ? "1 gos" : count + " gossos");
            }
        }
    }
}
