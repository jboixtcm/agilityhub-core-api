package com.agilityhub.core.platform.application;

/** Shared Accept-Language negotiation for slug-selected public club resources. */
public final class PublicClubLocale {
    private PublicClubLocale() { }
    public static java.util.Locale resolve(String language, com.agilityhub.core.platform.application.ClubConfig config) {
        if (language != null) {
            try {
                var allowed = config.club().locales().stream().map(java.util.Locale::forLanguageTag).toList();
                var ranges = java.util.Locale.LanguageRange.parse(language);
                var locale = java.util.Locale.lookup(ranges, allowed);
                if (locale != null) { return locale; }
                var matches = java.util.Locale.filter(ranges, allowed);
                if (!matches.isEmpty()) { return matches.getFirst(); }
            } catch (IllegalArgumentException invalid) { /* Fall back to the club locale for malformed headers. */ }
        }
        return java.util.Locale.forLanguageTag(config.club().defaultLocale());
    }
}
