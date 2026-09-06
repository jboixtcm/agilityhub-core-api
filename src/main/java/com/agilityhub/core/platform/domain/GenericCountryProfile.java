package com.agilityhub.core.platform.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;

public class GenericCountryProfile implements CountryProfile {
    @Override public String code() { return "GENERIC"; }
    @Override public List<String> idDocumentTypes() { return List.of("OTHER"); }
    @Override public boolean validateIdDocument(String type, String value) { return true; }
    @Override public boolean validateIban(String value) { return true; }
    @Override public List<Town> postalCodeLookup(String code) { return List.of(); }
    @Override public String defaultPhonePrefix() { return ""; }
    @Override public String dateFormat() { return "yyyy-MM-dd"; }
    @Override public String timeFormat() { return "HH:mm"; }
    @Override public String normalizePhone(String raw) {
        String normalized = raw == null ? "" : raw.replaceAll("[\\s().-]", "");
        if (!normalized.matches("\\+[1-9][0-9]{1,14}")) { throw new ApiException(ErrorCode.INVALID_PHONE); }
        return normalized;
    }
}
