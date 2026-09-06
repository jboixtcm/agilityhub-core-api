package com.agilityhub.core.platform.domain;

import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ParameterValidatorTest {
    final ParameterCatalog catalog = new ParameterCatalog(new ObjectMapper());
    final ParameterValidator validator = new ParameterValidator();
    void valid(String key, Object value) { validator.validate(catalog.get(key), value, "ca", "EUR"); }
    void invalid(String key, Object value) {
        assertThatThrownBy(() -> valid(key, value)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.PARAMETER_INVALID));
    }
    @Test void T_02_02_typesAndNumericConstraints() {
        invalid("bookings.maxCurrentWeek", "2"); invalid("bookings.maxCurrentWeek", 2.5); invalid("bookings.maxCurrentWeek", -1);
        valid("bookings.maxCurrentWeek", 2L); valid("bookings.maxCurrentWeek", (short) 2); valid("bookings.maxCurrentWeek", (byte) 2);
        invalid("bookings.maxCurrentWeek", null); invalid("signup.firstMonthSplitDay", 32);
        valid("signup.enabled", false); invalid("signup.enabled", "false");
        valid("bookings.limitUnit", "MEMBER"); invalid("bookings.limitUnit", "CAT");
        valid("messaging.email.fromAddress", "sender@example.test"); invalid("messaging.email.fromAddress", 2);
        valid("billing.entryFeePerDog", Map.of("amountMinor", 100, "currency", "EUR"));
        invalid("billing.entryFeePerDog", Map.of("amountMinor", 100, "currency", "USD"));
        invalid("billing.entryFeePerDog", Map.of("amountMinor", -1, "currency", "EUR"));
        invalid("billing.entryFeePerDog", Map.of("amountMinor", 1.5, "currency", "EUR"));
        valid("courses.gateClearanceMeters", 1.5); invalid("courses.gateClearanceMeters", "1.5");
        invalid("courses.gateClearanceMeters", Double.NaN); invalid("courses.placementMarginMeters", Double.POSITIVE_INFINITY);
        invalid("courses.placementMarginMeters", Map.of()); invalid("signup.rateLimit", "free text");
        valid("signup.rateLimit", Map.of("requestLimit", 1)); valid("signup.rateLimit", List.of());
        invalid("files.allowedTypes", "image/*");
    }
    @Test void T_02_02_localizedTextAndStructuredLists() {
        valid("signup.text.closed", Map.of("ca", "Closed")); invalid("signup.text.closed", Map.of("en", "Closed"));
        invalid("signup.text.closed", Map.of("ca", " ")); invalid("signup.text.closed", Map.of("ca", "Closed", "en", 2));
        valid("census.bookingBlockReasons", List.of(Map.of("ca", "Example")));
        invalid("census.bookingBlockReasons", List.of(Map.of("en", "Example")));
        valid("leave.reasons", List.of(Map.of("key", "OTHER", "label", Map.of("ca", "Other"))));
        invalid("leave.reasons", List.of(Map.of("key", "", "label", Map.of("ca", "Other"))));
        var entry = Map.of("key", "OTHER", "label", Map.of("ca", "Other"));
        invalid("leave.reasons", List.of(entry, entry)); invalid("leave.reasons", List.of(Map.of("label", Map.of("ca", "Other"))));
        valid("census.dogDocumentTypes", List.of(Map.of("key", "OTHER", "label", Map.of("ca", "Other"), "required", false)));
        invalid("census.dogDocumentTypes", List.of(entry));
    }
    @Test void T_01_26_onboardingFieldsUseOnlyUniqueCatalogKeysAndBooleanRequiredFlags() {
        valid("signup.onboardingFields", catalog.defaultValue("signup.onboardingFields"));
        valid("signup.onboardingFields", List.of());
        invalid("signup.onboardingFields", Map.of());
        invalid("signup.onboardingFields", List.of("name"));
        invalid("signup.onboardingFields", List.of(Map.of("required", true)));
        invalid("signup.onboardingFields", List.of(Map.of("key", "email", "required", true)));
        invalid("signup.onboardingFields", List.of(Map.of("key", "name", "required", "true")));
        var entry = Map.of("key", "phone", "required", false);
        invalid("signup.onboardingFields", List.of(entry, entry));
    }
    @Test void T_02_02_weekAndCoverageValidators() {
        invalid("bookings.weekOpensAt", Map.of("dayOfWeek", "INVALID", "time", "20:00"));
        invalid("bookings.weekOpensAt", Map.of("dayOfWeek", "SUNDAY", "time", "24:00"));
        invalid("classes.riskReviewTime", "7:30"); invalid("classes.riskReviewTime", "07:30:01");
        valid("coverage.thresholds", Map.of("ok", 240, "tight", 190, "short", 150));
        invalid("coverage.thresholds", Map.of("ok", 190, "tight", 190, "short", 150));
        invalid("coverage.thresholds", Map.of("ok", 240, "tight", 150, "short", 150));
        invalid("coverage.thresholds", Map.of("ok", 240, "tight", 150, "short", -1));
        invalid("coverage.thresholds", Map.of("ok", Double.NaN, "tight", 190, "short", 150));
    }
    @Test void T_02_16_openingHoursAndHolidays() {
        valid("club.openingHours", Map.of());
        valid("club.openingHours", Map.of("MONDAY", Map.of("open", "07:00", "close", "22:00")));
        invalid("club.openingHours", Map.of("MONDAY", Map.of("open", "22:00", "close", "22:00")));
        invalid("club.openingHours", Map.of("MONDAY", Map.of("open", "23:00", "close", "22:00")));
        invalid("club.openingHours", Map.of("MONDAY", Map.of("open", "07:01", "close", "22:00")));
        invalid("club.openingHours", Map.of("MONDAY", Map.of("open", "07:00", "close", "22:01")));
        invalid("club.openingHours", Map.of("FUNDAY", Map.of("open", "07:00", "close", "22:00")));
        valid("club.holidays", List.of("2030-01-01", Map.of("date", "2030-12-25", "label", "Example holiday")));
        invalid("club.holidays", List.of("2030-02-30")); invalid("club.holidays", List.of(Map.of("date", "2030-01-01")));
    }
    @Test void T_02_02_invalidCatalogSchemasAndPatternsFailClosed() {
        var original = catalog.get("messaging.email.fromAddress");
        for (String type : List.of("string", "unsupported")) {
            var definition = new ParameterDefinition(original.key(), type, null, "system", "label", "help",
                    Map.of("pattern", "[a-z]+"), List.of(), "club", "PLATFORM", false, type, "—");
            assertThatThrownBy(() -> validator.validate(definition, "INVALID", "ca", "EUR")).isInstanceOf(ApiException.class);
            if (type.equals("string")) { validator.validate(definition, "valid", "ca", "EUR"); }
        }
        var unknownValidator = new ParameterDefinition("test", "string", null, "system", "label", "help",
                Map.of("validator", "unsupported"), List.of(), "club", "PLATFORM", false, "string", "—");
        assertThatThrownBy(() -> validator.validate(unknownValidator, "value", "ca", "EUR")).isInstanceOf(ApiException.class);
    }
}
