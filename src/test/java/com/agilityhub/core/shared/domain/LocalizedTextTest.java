package com.agilityhub.core.shared.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalizedTextTest {
    @Test void E0_T04_resolvesRequestedDefaultThenFirstAvailable() {
        var values = new LinkedHashMap<String, String>();
        values.put("ca", "Hola"); values.put("es", "Buenas");
        var text = new LocalizedText(values, "ca");
        values.put("ca", "Changed");
        assertThat(text.resolve(Locale.forLanguageTag("es"))).isEqualTo(new LocalizedText.ResolvedText("Buenas", false));
        assertThat(text.resolve("en")).isEqualTo(new LocalizedText.ResolvedText("Hola", true));
        assertThat(text.withDefaultLocale("es").resolve("en").value()).isEqualTo("Buenas");
        assertThat(text.withDefaultLocale("de").resolve("en")).isEqualTo(new LocalizedText.ResolvedText("Hola", true));
        assertThatThrownBy(() -> text.values().put("en", "Hello")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new LocalizedText(Map.of(), "ca")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void E0_T04_jacksonUsesOnlyTheLocaleMap() throws Exception {
        var mapper = new ObjectMapper();
        var text = new LocalizedText(Map.of("ca", "Hola", "es", "Buenas"), "ca");
        assertThat(mapper.readTree(mapper.writeValueAsString(text)))
                .isEqualTo(mapper.readTree("{\"ca\":\"Hola\",\"es\":\"Buenas\"}"));
        var read = mapper.readValue("{\"es\":\"Buenas\"}", LocalizedText.class).withDefaultLocale("ca");
        assertThat(read.resolve("en")).isEqualTo(new LocalizedText.ResolvedText("Buenas", true));
    }
}
