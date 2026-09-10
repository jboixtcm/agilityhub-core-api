package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.TenantContext;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CountryContacts {
    private final ClubConfigService configs;
    public CountryContacts(ClubConfigService configs) { this.configs = configs; }
    public Map<String,Object> phone(String prefix, String number, String label) {
        var phone = new CountryContactRules(configs.get(TenantContext.require()).countryProfile()).phone(prefix, number, label);
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("prefix", phone.prefix()); result.put("number", phone.number());
        if (phone.label() != null) { result.put("label", phone.label()); }
        return result;
    }
    public String normalizeDocument(String type, String value) {
        return new CountryContactRules(configs.get(TenantContext.require()).countryProfile()).document(type, value);
    }

    public boolean document(String type, String number) { return configs.get(TenantContext.require()).countryProfile().validateIdDocument(type, number); }
    public boolean postalCode(String code) {
        return !"ES".equals(configs.get(TenantContext.require()).countryProfile().code()) || (code != null && code.matches("[0-9]{5}"));
    }
    public String countryCode() { return configs.get(TenantContext.require()).countryProfile().code(); }
    public boolean iban(String value) { return configs.get(TenantContext.require()).countryProfile().validateIban(value); }
}
