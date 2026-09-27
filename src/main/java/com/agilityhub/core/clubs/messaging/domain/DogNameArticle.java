package com.agilityhub.core.clubs.messaging.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * `dog_name_article` (S11 R-11-05, §10): the dog's name with the Catalan personal article — «la Duna», «en Rock», «l'Ares»
 * (a name that starts with a vowel sound, `h` included, takes `l'` whatever the sex) — and the bare name in any other
 * language. A dog without a known sex (a migrated one, S18) keeps the bare name. Back-end copy of the rule S08 shares with
 * `packages/i18n/format.ts`.
 */
public final class DogNameArticle {
    private DogNameArticle() { }

    /** @param sex `FEMALE`, `MALE` or anything else (unknown) */
    public static String of(String name, String sex, Locale locale) {
        if (name == null || name.isBlank()) { return ""; }
        String trimmed = name.strip();
        if (locale == null || !"ca".equals(locale.getLanguage())) { return trimmed; }
        boolean female = "FEMALE".equalsIgnoreCase(sex), male = "MALE".equalsIgnoreCase(sex);
        if (!female && !male) { return trimmed; }
        if (vowelSound(trimmed)) { return "l'" + trimmed; }
        return (female ? "la " : "en ") + trimmed;
    }

    private static boolean vowelSound(String name) {
        String plain = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (plain.startsWith("h")) { plain = plain.substring(1); }
        return !plain.isEmpty() && "aeiou".indexOf(plain.charAt(0)) >= 0;
    }
}
