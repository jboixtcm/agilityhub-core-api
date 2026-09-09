package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.google.i18n.phonenumbers.*;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CountryContacts {
    private final ClubConfigService configs;
    public CountryContacts(ClubConfigService configs) { this.configs = configs; }
    public Map<String,Object> phone(String prefix, String number, String label) {
        var profile = configs.get(TenantContext.require()).countryProfile();
        String raw = number == null ? "" : number.replaceAll("[\\s().-]", "");
        if (!raw.startsWith("+") && !raw.startsWith("00")) { raw = (prefix == null || prefix.isBlank() ? profile.defaultPhonePrefix() : prefix) + raw; }
        String normalized = profile.normalizePhone(raw);
        try {
            var util = PhoneNumberUtil.getInstance(); var parsed = util.parse(normalized, "ZZ");
            String country = "+" + parsed.getCountryCode();
            if (prefix != null && !prefix.isBlank() && !country.equals(prefix)) { throw new ApiException(ErrorCode.INVALID_PHONE); }
            if (label != null && label.length() > 30) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            var result = new java.util.LinkedHashMap<String,Object>(); result.put("prefix", country);
            result.put("number", normalized.substring(country.length())); if (label != null) { result.put("label", label.strip()); } return result;
        } catch (NumberParseException invalid) { throw new ApiException(ErrorCode.INVALID_PHONE); }
    }
    public boolean document(String type, String number) { return configs.get(TenantContext.require()).countryProfile().validateIdDocument(type, number); }
    public boolean postalCode(String code) {
        return !"ES".equals(configs.get(TenantContext.require()).countryProfile().code()) || (code != null && code.matches("[0-9]{5}"));
    }
    public String countryCode() { return configs.get(TenantContext.require()).countryProfile().code(); }
    public boolean iban(String value) { return configs.get(TenantContext.require()).countryProfile().validateIban(value); }
}
