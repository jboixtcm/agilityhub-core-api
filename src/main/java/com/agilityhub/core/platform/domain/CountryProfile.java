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
    String normalizePhone(String raw);
    boolean validateIban(String value);
    List<Town> postalCodeLookup(String code);
    String defaultPhonePrefix();
    String dateFormat();
    String timeFormat();
    record Town(String town, String region) { }
}
