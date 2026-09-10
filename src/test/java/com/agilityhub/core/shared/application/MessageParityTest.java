package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ErrorCode;
import com.ibm.icu.text.MessageFormat;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MessageParityTest {
    @Test void E0_T08_everyErrorHasANonemptyValidIcuMessageInAllProductLocales() throws Exception {
        var source = new IcuMessageSource();
        assertThat(source.supportedLocales()).containsExactlyInAnyOrder(
                Locale.forLanguageTag("ca"), Locale.forLanguageTag("es"), Locale.ENGLISH);
        java.util.Set<String> keys = null;
        var errorKeys = Arrays.stream(ErrorCode.values()).map(code -> "error." + code).toList();
        for (Locale locale : source.supportedLocales()) {
            var properties = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    assertThat(containsKey(key)).as("duplicate key %s in %s", key, locale).isFalse();
                    return super.put(key, value);
                }
            };
            try (var stream = getClass().getResourceAsStream("/messages/messages_" + locale + ".properties");
                 var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            if (keys == null) { keys = properties.stringPropertyNames(); }
            assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(keys);
            assertThat(properties.stringPropertyNames().stream().filter(key -> key.startsWith("error.")))
                    .containsExactlyInAnyOrderElementsOf(errorKeys);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                assertThat(value).as("%s %s", locale, key).isNotBlank();
                var format = new MessageFormat(value, locale);
                if (key.startsWith("error.")) {
                    if (key.equals("error.INTERNAL_ERROR")) {
                        assertThat(format.getArgumentNames()).containsExactly("traceId");
                        assertThat(source.format(key, java.util.Map.of("traceId", "fictional-trace"), locale))
                                .contains("fictional-trace").doesNotContain("{traceId}");
                    } else {
                        assertThat(format.getArgumentNames()).isEmpty();
                        assertThat(source.getMessage(key, null, locale)).isEqualTo(value);
                    }
                }
            }
            System.out.println("E0-T08 " + locale + ": " + errorKeys.size() + " error messages; " + properties.size() + " keys; parity and ICU syntax OK");
        }
    }
}
