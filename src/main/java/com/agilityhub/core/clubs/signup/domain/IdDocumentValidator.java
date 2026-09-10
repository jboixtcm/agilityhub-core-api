package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.platform.application.CountryContactRules;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

public final class IdDocumentValidator {
    private IdDocumentValidator() { }
    public record IdDocument(String type, String value) { }
    public static IdDocument validate(CountryContactRules country, String type, String value) {
        return new IdDocument(type, country.document(type, value));
    }
    /** Legacy two-field forms must never silently discard one of the supplied documents. */
    public static IdDocument spanish(CountryContactRules country, String dniOrNie, String passport) {
        boolean national = dniOrNie != null && !dniOrNie.isBlank();
        boolean travel = passport != null && !passport.isBlank();
        if (national && travel) { throw new ApiException(ErrorCode.ID_DOCUMENT_AMBIGUOUS); }
        String type = national ? (dniOrNie.strip().matches("(?i)[XYZ].*") ? "NIE" : "DNI") : "PASSPORT";
        return validate(country, type, national ? dniOrNie : passport);
    }
}
