package com.agilityhub.core.platform.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;

public class GenericCountryProfile implements CountryProfile {
    @Override public String code() { return "GENERIC"; }
    @Override public List<String> idDocumentTypes() { return List.of("PASSPORT", "OTHER"); }
    @Override public boolean validateIdDocument(String type, String value) {
        return type != null && idDocumentTypes().contains(type) && normalizeIdDocument(type, value).length() >= 4;
    }
    /** S02 §2, R-02-06: `GENERIC` does not validate the tax id («cap validació de NIF»), so any value is accepted. */
    @Override public boolean validateTaxId(String value) { return true; }
    /**
     * R-04-10 (E3-T10): without a country table, an IBAN still has the ISO 13616 shape (country letters, two check digits,
     * 11–30 alphanumerics) and must pass the mod-97 check.
     */
    @Override public boolean validateIban(String value) {
        if (value == null) { return false; }
        String iban = value.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        return iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")
                && org.apache.commons.validator.routines.checkdigit.IBANCheckDigit.IBAN_CHECK_DIGIT.isValid(iban);
    }
    @Override public List<Town> postalCodeLookup(String code) { return List.of(); }
    @Override public String defaultPhonePrefix() { return ""; }
    @Override public String dateFormat() { return "yyyy-MM-dd"; }
    @Override public String timeFormat() { return "HH:mm"; }
    @Override public String normalizePhone(String raw) {
        String normalized = raw == null ? "" : raw.replaceAll("[\\s().-]", "");
        if (!normalized.matches("\\+[1-9][0-9]{6,14}")) { throw new ApiException(ErrorCode.INVALID_PHONE); }
        return normalized;
    }
}
