package com.agilityhub.core.platform.domain;

import java.util.List;

public interface CountryProfile {
    String code();
    List<String> idDocumentTypes();
    boolean validateIdDocument(String type, String value);
    String normalizePhone(String raw);
    boolean validateIban(String value);
    List<Town> postalCodeLookup(String code);
    String defaultPhonePrefix();
    String dateFormat();
    String timeFormat();
    record Town(String town, String region) { }
}
