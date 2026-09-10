package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.platform.application.CountryContactRules;
import java.util.List;

public final class PostalCodeTowns {
    private PostalCodeTowns() { }
    public enum Selection { FREE_TEXT, PREFILLED, CHOICE }
    public record Result(List<CountryContactRules.Town> towns, Selection selection, String defaultTown) {
        public Result { towns = List.copyOf(towns); }
    }
    public static Result lookup(CountryContactRules country, String postalCode) {
        var towns = country.towns(postalCode);
        return new Result(towns, towns.isEmpty() ? Selection.FREE_TEXT : towns.size() == 1 ? Selection.PREFILLED : Selection.CHOICE,
                towns.isEmpty() ? null : towns.getFirst().name());
    }
    public static String select(Result result, String town) {
        if (town == null || town.isBlank()) {
            if (result.defaultTown() == null) { throw SignupValidation.field("address.town"); }
            return result.defaultTown();
        }
        String value = town.strip();
        if (!result.towns().isEmpty() && result.towns().stream().noneMatch(t -> t.name().equals(value))) {
            throw SignupValidation.field("address.town");
        }
        return value;
    }
}
