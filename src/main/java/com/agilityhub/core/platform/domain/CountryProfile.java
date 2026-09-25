package com.agilityhub.core.platform.domain;

import java.util.List;

public interface CountryProfile {
    String code();
    List<String> idDocumentTypes();
    default String normalizeIdDocument(String type, String value) {
        return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s-]", "");
    }
    boolean validateIdDocument(String type, String value);
    /**
     * S02 §3: the club's `taxId` (its organisation identifier), checked by its country profile when it changes; a profile
     * switch does not re-check a stored one (R-02-06).
     */
    boolean validateTaxId(String value);
    /**
     * E5-T16 (review E3-T16 #3; S02 §3, R-02-06): the stored form of a club's `taxId`, whatever its profile: upper case,
     * without spaces or separators (every character that is not a letter or a digit), `null` when nothing is left. The
     * club stores, compares and publishes this form, so the same id written with other spacing is no change.
     */
    static String normalizeTaxId(String value) {
        if (value == null) { return null; }
        String normalized = value.replaceAll("[^\\p{L}\\p{N}]", "").toUpperCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
    String normalizePhone(String raw);
    boolean validateIban(String value);
    List<Town> postalCodeLookup(String code);
    String defaultPhonePrefix();
    String dateFormat();
    String timeFormat();
    record Town(String town, String region) { }
}
