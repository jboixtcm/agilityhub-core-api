package com.agilityhub.core.clubs.signup.domain;

import java.util.Locale;

/**
 * S04 §3 `Dog.chip` per country profile: `ES` → 15 digits, `GENERIC` → 8–15 alphanumerics. The value is normalised
 * (spaces and dashes removed, upper case) before the format check, the uniqueness check (R-04-07) and the readmission match.
 */
public final class DogChips {
    private DogChips() { }
    public static String normalize(String raw) { return raw == null ? null : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT); }
    public static boolean valid(String countryProfile, String chip) {
        if (chip == null) { return false; }
        return "ES".equals(countryProfile) ? chip.matches("\\d{15}") : chip.matches("[A-Z0-9]{8,15}");
    }
}
