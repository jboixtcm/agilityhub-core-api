package com.agilityhub.core.shared.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record LocalizedText(@JsonValue Map<String, String> values, String defaultLocale) {
    public LocalizedText {
        Objects.requireNonNull(defaultLocale);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("LocalizedText requires at least one translation");
        }
        values.forEach((key, value) -> { Objects.requireNonNull(key); Objects.requireNonNull(value); });
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    // The wire map has no club default. Call withDefaultLocale after resolving the club.
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LocalizedText fromJson(Map<String, String> values) {
        return new LocalizedText(values, "und");
    }

    public LocalizedText withDefaultLocale(String locale) {
        return new LocalizedText(values, locale);
    }

    public ResolvedText resolve(Locale locale) {
        return resolve(locale.toLanguageTag());
    }

    public ResolvedText resolve(String locale) {
        String value = values.get(locale);
        if (value != null) {
            return new ResolvedText(value, false);
        }
        return new ResolvedText(values.getOrDefault(defaultLocale, values.values().iterator().next()), true);
    }

    public record ResolvedText(String value, boolean fallback) { }
}
