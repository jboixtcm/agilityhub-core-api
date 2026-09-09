package com.agilityhub.core.clubs.content.domain;

import com.agilityhub.core.shared.domain.*;
import java.util.*;

public record PageContent(String key, LocalizedText title, LocalizedText body, boolean active) {
    public static final String KEY_PATTERN = "(?:RULES|PRIVACY|IMAGE_CONSENT|WELCOME_GUIDE|[a-z0-9-]{2,40})";
    public static PageContent validate(String key, Map<String, String> title, Map<String, String> body,
            boolean active, String defaultLocale, List<String> locales) {
        if (key == null || !key.matches(KEY_PATTERN)) { throw invalid("key"); }
        return new PageContent(key, text(title, "title", 200, defaultLocale, locales),
                text(body, "body", 20000, defaultLocale, locales), active);
    }
    private static LocalizedText text(Map<String, String> values, String field, int max, String defaultLocale, List<String> locales) {
        if (values == null || !values.containsKey(defaultLocale)) { throw invalid(field); }
        // Seed-required translations may be prepared before their UI locale is enabled.
        values.forEach((locale, value) -> {
            if ((!locales.contains(locale) && !Set.of("ca", "es", "en").contains(locale)) || value == null || value.isBlank() || value.length() > max) { throw invalid(field + "." + locale); }
            if (field.equals("body")) { LimitedMarkdown.validate(value, field + "." + locale); }
        });
        return new LocalizedText(values, defaultLocale);
    }
    public static ApiException invalid(String field) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", "VALIDATION_ERROR"))));
    }
}
