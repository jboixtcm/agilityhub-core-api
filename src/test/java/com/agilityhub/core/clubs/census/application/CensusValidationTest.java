package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.CountryContacts;
import com.agilityhub.core.shared.domain.*;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

class CensusValidationTest {
    private final CountryContacts countries = mock(CountryContacts.class);
    private final jakarta.validation.ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final CensusValidation validation = new CensusValidation(countries, factory.getValidator());
    @AfterEach void close() { factory.close(); }

    @Test void T_03_12_versionsAndJsonValueTypesAreStrict() {
        version(2, 2); version(2, 2L);
        assertThatThrownBy(() -> version(2, null)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> version(2, "2")).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> version(0, 0.5)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> version(0, -1)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> version(2, 1)).hasMessage("STALE_VERSION");
        assertThatThrownBy(() -> rows(List.of(1))).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> rows("no")).hasMessage("VALIDATION_ERROR");
        assertThat(rows(null)).isEmpty(); assertThat(rows(List.of(Map.of("name", "Example")))).hasSize(1);
        assertThat(map(null)).isEmpty(); assertThat(text(null, "name", 20, false)).isNull();
        for (Object value : List.of(42, "", " ", "x".repeat(21))) { assertThatThrownBy(() -> text(value, "name", 20, true)).hasMessage("VALIDATION_ERROR"); }
        assertThat(text(" Example ", "name", 20, true)).isEqualTo("Example");
        assertThat(date(Date.from(Instant.parse("2026-01-02T00:00:00Z")))).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(instant("2026-01-02T00:00:00Z")).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(date(null)).isNull(); assertThat(instant(null)).isNull(); assertThat(number(null)).isZero();
    }

    @Test void T_03_07_emailEditsPreserveOnlyExistingBounceState() {
        var old = List.of(object("email", "first@example.test", "bounced", true), object("email", "second@example.test", "bounced", false));
        var result = validation.emails(List.of(Map.of("email", "FIRST@EXAMPLE.TEST"), Map.of("email", "new@example.test")), old);
        assertThat(result.getFirst()).containsEntry("bounced", true); assertThat(result.getLast()).containsEntry("bounced", false);
        assertThat(validation.emails(List.of(Map.of("email", "first@example.test")), null).getFirst()).containsEntry("bounced", false);
        assertThat(validation.emails(List.of(Map.of("email", "second@example.test")), old).getFirst()).containsEntry("bounced", false);
        for (Object raw : Arrays.asList(null, List.of(), List.of(Map.of("email", "bad")),
                List.of(Map.of("email", "same@example.test"), Map.of("email", "SAME@example.test")),
                List.of(Map.of("email", "first@example.test", "bounced", true)), List.of(Map.of(), Map.of(), Map.of()))) {
            assertThatThrownBy(() -> validation.emails(raw, old)).hasMessage("VALIDATION_ERROR");
        }
    }

    @Test void T_03_07_phonesAddressesAndDocumentsUseTheCountryProfile() {
        when(countries.phone(null, "600000001", "Example")).thenReturn(object("prefix", "+34", "number", "600000001", "label", "Example"));
        assertThat(validation.phones(List.of(Map.of("number", "600000001", "label", "Example"))).getFirst()).containsEntry("prefix", "+34");
        when(countries.phone(null, "bad", null)).thenThrow(new ApiException(ErrorCode.INVALID_PHONE));
        for (Object raw : Arrays.asList(null, List.of(), List.of(Map.of(), Map.of(), Map.of()), List.of(Map.of("number", "bad")),
                List.of(Map.of("number", "600000001", "label", "x".repeat(31))))) {
            assertThatThrownBy(() -> validation.phones(raw)).hasMessage("VALIDATION_ERROR");
        }
        when(countries.postalCode("08349")).thenReturn(true);
        assertThat(validation.address(Map.of("street", "Example", "city", "Town", "postalCode", "08349", "province", "Example", "country", "ES"))).containsEntry("postalCode", "08349");
        assertThatThrownBy(() -> validation.address(Map.of("postalCode", "bad"))).hasMessage("VALIDATION_ERROR");
        when(countries.document("DNI", "12345678Z")).thenReturn(true);
        assertThat(validation.idDocument(Map.of("type", "DNI", "number", "12345678z"))).containsEntry("number", "12345678Z");
        assertThatThrownBy(() -> validation.idDocument(Map.of("type", "DNI", "number", "12345678A"))).hasMessage("VALIDATION_ERROR");
    }

    @Test void T_03_32_licenseLimitsAndUniqueOrganisationsAreValidated() {
        assertThat(validation.licenses(List.of())).isEmpty();
        assertThat(validation.licenses(List.of(Map.of("organisation", "Example", "number", "1")))).hasSize(1);
        for (var entry : Map.of("organisation", 20, "number", 30, "grade", 30, "category", 10, "division", 20).entrySet()) {
            var row = new HashMap<String,Object>(Map.of("organisation", "Example", "number", "1")); row.put(entry.getKey(), "x".repeat(entry.getValue()));
            assertThat(validation.licenses(List.of(row))).hasSize(1);
            row.put(entry.getKey(), "x".repeat(entry.getValue() + 1)); assertThatThrownBy(() -> validation.licenses(List.of(row))).hasMessage("VALIDATION_ERROR");
        }
        assertThatThrownBy(() -> validation.licenses(null)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> validation.licenses(List.of(Map.of("organisation", "Example", "number", "1"), Map.of("organisation", "example", "number", "2")))).hasMessage("VALIDATION_ERROR");
    }
}
