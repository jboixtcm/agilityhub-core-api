package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.platform.application.CountryContactRules;
import java.util.ArrayList;
import java.util.List;

public final class PhoneNormalizer {
    private PhoneNormalizer() { }
    public record Input(String prefix, String number, String label) { }
    public static List<CountryContactRules.Phone> normalize(CountryContactRules country, List<Input> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 2) { throw SignupValidation.field("phones"); }
        var result = new ArrayList<CountryContactRules.Phone>();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            if (i > 0 && (input.label() == null || input.label().isBlank())) { throw SignupValidation.field("phones[" + i + "].label"); }
            result.add(country.phone(input.prefix(), input.number(), input.label()));
        }
        return List.copyOf(result);
    }
}
