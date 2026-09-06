package com.agilityhub.core.shared.application;

import java.util.List;
import java.util.Optional;

/** Club locale settings without exposing platform persistence to shared infrastructure. */
public interface LocaleSettingsProvider {
    Optional<Settings> settings(String clubId);

    record Settings(List<String> locales, String defaultLocale) {
        public Settings { locales = List.copyOf(locales); }
    }
}
