package com.agilityhub.core.platform.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.validator.routines.IBANValidator;

public class SpanishCountryProfile extends GenericCountryProfile {
    private static final String LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";
    private final Map<String, List<Town>> towns;
    public SpanishCountryProfile(Map<String, List<Town>> towns) { this.towns = Map.copyOf(towns); }
    @Override public String code() { return "ES"; }
    @Override public List<String> idDocumentTypes() { return List.of("DNI", "NIE", "PASSPORT"); }
    @Override public String defaultPhonePrefix() { return "+34"; }
    @Override public String dateFormat() { return "dd/MM/yyyy"; }
    @Override public boolean validateIdDocument(String type, String value) {
        if (value == null || value.isBlank()) { return false; }
        String document = value.toUpperCase(Locale.ROOT).strip();
        if ("PASSPORT".equals(type)) { return true; }
        if ("DNI".equals(type) && document.matches("[0-9]{8}[A-Z]")) { return checkLetter(document); }
        if ("NIE".equals(type) && document.matches("[XYZ][0-9]{7}[A-Z]")) {
            return checkLetter("XYZ".indexOf(document.charAt(0)) + document.substring(1));
        }
        return false;
    }
    private boolean checkLetter(String value) {
        return LETTERS.charAt(Integer.parseInt(value.substring(0, 8)) % 23) == value.charAt(8);
    }
    @Override public String normalizePhone(String raw) {
        String phone = raw == null ? "" : raw.replaceAll("[\\s().-]", "");
        if (phone.matches("[0-9]{9}")) { phone = defaultPhonePrefix() + phone; }
        if (phone.startsWith("00")) { phone = "+" + phone.substring(2); }
        if (phone.startsWith("+34") && !phone.matches("\\+34[0-9]{9}")) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.INVALID_PHONE);
        }
        return super.normalizePhone(phone);
    }
    @Override public boolean validateIban(String value) {
        return value != null && IBANValidator.getInstance().isValid(value.replaceAll("\\s", "").toUpperCase(Locale.ROOT));
    }
    @Override public List<Town> postalCodeLookup(String code) { return towns.getOrDefault(code, List.of()); }
}
