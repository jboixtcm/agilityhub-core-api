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
    @Override public String normalizeIdDocument(String type, String value) {
        String normalized = super.normalizeIdDocument(type, value);
        return "DNI".equals(type) && normalized.matches("[0-9]{7}[A-Z]") ? "0" + normalized : normalized;
    }
    @Override public boolean validateIdDocument(String type, String value) {
        if (value == null || value.isBlank()) { return false; }
        String document = normalizeIdDocument(type, value);
        if ("PASSPORT".equals(type)) { return document.matches("[A-Z0-9]{5,20}"); }
        if ("DNI".equals(type) && document.matches("[0-9]{8}[A-Z]")) { return checkLetter(document); }
        if ("NIE".equals(type) && document.matches("[XYZ][0-9]{7}[A-Z]")) {
            return checkLetter("XYZ".indexOf(document.charAt(0)) + document.substring(1));
        }
        return false;
    }
    private boolean checkLetter(String value) {
        return LETTERS.charAt(Integer.parseInt(value.substring(0, 8)) % 23) == value.charAt(8);
    }
    /** S02 §3: an organisation's CIF (the Cànic's G63189617), or a person's NIF (DNI) or NIE, with its check character. */
    @Override public boolean validateTaxId(String value) {
        String id = CountryProfile.normalizeTaxId(value);
        if (id == null) { return false; }
        return checkCif(id) || validateIdDocument("DNI", id) || validateIdDocument("NIE", id);
    }
    /**
     * The CIF control: the digits in even positions are added, those in odd positions doubled and their digits added; the
     * control is the complement to 10 of the total's last digit, written as a digit or as the letter of "JABCDEFGHI". K, L,
     * M, N, P, Q, R, S and W take the letter; A, B, E and H the digit; the other entity letters either.
     */
    private static boolean checkCif(String value) {
        if (!value.matches("[ABCDEFGHJKLMNPQRSUVW][0-9]{7}[0-9A-J]")) { return false; }
        int total = 0;
        for (int position = 1; position <= 7; position++) {
            int digit = value.charAt(position) - '0';
            total += position % 2 == 0 ? digit : digit * 2 / 10 + digit * 2 % 10;
        }
        int control = (10 - total % 10) % 10;
        char actual = value.charAt(8), kind = value.charAt(0);
        boolean asDigit = actual == (char) ('0' + control), asLetter = actual == "JABCDEFGHI".charAt(control);
        if ("KLMNPQRSW".indexOf(kind) >= 0) { return asLetter; }
        if ("ABEH".indexOf(kind) >= 0) { return asDigit; }
        return asDigit || asLetter;
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
