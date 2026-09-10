package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.CountryProfile;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.util.List;

/** Pure country-profile access shared by census and signup; no tenant lookup or I/O. */
public final class CountryContactRules {
    private final CountryProfile profile;
    public CountryContactRules(CountryProfile profile) { this.profile = profile; }
    public String code() { return profile.code(); }
    public String document(String type, String value) {
        String normalized = profile.normalizeIdDocument(type, value);
        if (!profile.validateIdDocument(type, normalized)) { throw new ApiException(ErrorCode.INVALID_ID_DOCUMENT); }
        return normalized;
    }
    public record Phone(String prefix, String number, String label) {
        public String e164() { return prefix + number; }
    }
    public Phone phone(String prefix, String number, String label) {
        String raw = number == null ? "" : number.replaceAll("[\\s().-]", "");
        if (!raw.startsWith("+") && !raw.startsWith("00")) {
            raw = (prefix == null || prefix.isBlank() ? profile.defaultPhonePrefix() : prefix) + raw;
        }
        if (raw.startsWith("00")) { raw = "+" + raw.substring(2); }
        String normalized = profile.normalizePhone(raw);
        try {
            var parsed = PhoneNumberUtil.getInstance().parse(normalized, "ZZ");
            String country = "+" + parsed.getCountryCode();
            if (prefix != null && !prefix.isBlank() && !country.equals(prefix)) { throw new ApiException(ErrorCode.INVALID_PHONE); }
            if (label != null && label.length() > 30) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            return new Phone(country, normalized.substring(country.length()), label == null ? null : label.strip());
        } catch (NumberParseException invalid) { throw new ApiException(ErrorCode.INVALID_PHONE); }
    }
    public record Town(String name, String region) { }
    public List<Town> towns(String postalCode) {
        boolean valid = postalCode != null && ("ES".equals(code())
                ? postalCode.matches("[0-9]{5}") : postalCode.strip().length() >= 3 && postalCode.strip().length() <= 10);
        if (!valid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        return profile.postalCodeLookup(postalCode.strip()).stream().map(t -> new Town(t.town(), t.region())).toList();
    }
}
