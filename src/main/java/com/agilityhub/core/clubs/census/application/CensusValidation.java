package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.CountryContacts;
import com.agilityhub.core.shared.domain.*;
import java.util.*;

import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class CensusValidation {
    private final jakarta.validation.Validator validator;
    private final CountryContacts countries;
    private record EmailInput(@jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank String email) { }
    public CensusValidation(CountryContacts countries, jakarta.validation.Validator validator) { this.countries = countries; this.validator = validator; }
    public List<Map<String,Object>> emails(Object raw, List<Map<String,Object>> before) {
        var emails = rows(raw); if (emails.isEmpty() || emails.size() > 2) { throw invalid("contactEmails", "INVALID_EMAIL"); }
        var values = new HashSet<String>(); var result = new ArrayList<Map<String,Object>>();
        for (var email : emails) {
            allow(email, Set.of("email")); String value = text(email.get("email"), "contactEmails", 254, true).toLowerCase(Locale.ROOT);
            if (!validator.validate(new EmailInput(value)).isEmpty() || !values.add(value)) { throw invalid("contactEmails", "INVALID_EMAIL"); }
            boolean bounced = before != null && before.stream().anyMatch(old -> value.equals(old.get("email")) && Boolean.TRUE.equals(old.get("bounced")));
            result.add(object("email", value, "bounced", bounced));
        }
        return result;
    }
    public List<Map<String,Object>> phones(Object raw) {
        var phones = rows(raw); if (phones.isEmpty() || phones.size() > 2) { throw invalid("phones", "INVALID_PHONE"); }
        var result = new ArrayList<Map<String,Object>>();
        for (var phone : phones) {
            allow(phone, Set.of("prefix", "number", "label"));
            try { result.add(countries.phone(string(phone.get("prefix")), string(phone.get("number")), text(phone.get("label"), "phones.label", 30, false))); }
            catch (ApiException badPhone) { throw invalid("phones", "INVALID_PHONE"); }
        }
        return result;
    }
    public Map<String,Object> address(Object raw) {
        var address = map(raw); allow(address, Set.of("street", "postalCode", "city", "province", "country"));
        String postal = text(address.get("postalCode"), "address.postalCode", 20, true);
        if (!countries.postalCode(postal)) { throw invalid("address.postalCode", "INVALID_POSTAL_CODE"); }
        return object("street", text(address.get("street"), "address.street", 200, true), "postalCode", postal,
                "city", text(address.get("city"), "address.city", 100, true), "province", text(address.get("province"), "address.province", 100, false),
                "country", text(address.get("country"), "address.country", 80, false));
    }
    public Map<String,Object> idDocument(Object raw) {
        var doc = map(raw); allow(doc, Set.of("type", "number"));
        String type = text(doc.get("type"), "idDocument.type", 30, true);
        String number = text(doc.get("number"), "idDocument.number", 80, true).toUpperCase(Locale.ROOT);
        if (!countries.document(type, number)) { throw invalid("idDocument", "INVALID_VALUE"); }
        return object("type", type, "number", countries.normalizeDocument(type, number));
    }
    public List<Map<String,Object>> licenses(Object raw) {
        if (!(raw instanceof List<?>)) { throw invalid("licenses", "INVALID_VALUE"); }
        var result = new ArrayList<Map<String,Object>>(); var orgs = new HashSet<String>();
        for (var row : rows(raw)) {
            allow(row, Set.of("organisation", "number", "category", "grade", "division"));
            String org = text(row.get("organisation"), "licenses.organisation", 20, true);
            if (!orgs.add(org.toLowerCase(Locale.ROOT))) { throw invalid("licenses", "DUPLICATE"); }
            result.add(object("organisation", org, "number", text(row.get("number"), "licenses.number", 30, true),
                    "category", text(row.get("category"), "licenses.category", 10, false), "grade", text(row.get("grade"), "licenses.grade", 30, false),
                    "division", text(row.get("division"), "licenses.division", 20, false)));
        }
        return result;
    }
}
