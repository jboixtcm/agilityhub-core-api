package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.platform.application.CountryContactRules;
import com.agilityhub.core.platform.application.CountryProfileRegistry;
import com.agilityhub.core.platform.domain.CountryProfile.Town;
import com.agilityhub.core.platform.domain.GenericCountryProfile;
import com.agilityhub.core.platform.domain.SpanishCountryProfile;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class SignupContactsTest {
    private final CountryContactRules es = new CountryContactRules(new SpanishCountryProfile(Map.of(
            "08349", List.of(new Town("Cabrera de Mar", "Barcelona")),
            "12345", List.of(new Town("Town A", "Region"), new Town("Town B", "Region"), new Town("Town C", "Region")))));
    private final CountryContactRules generic = new CountryContactRules(new GenericCountryProfile());

    @ParameterizedTest
    @CsvSource({"DNI,12345678Z,12345678Z", "NIE,x1234567-l,X1234567L", "NIE,Y1234567X,Y1234567X",
            "NIE,Z1234567R,Z1234567R", "DNI,1234567L,01234567L", "DNI,12 345 678-z,12345678Z"})
    void T_04_01_spanishDocumentsNormalizeAndCheckLetters(String type, String input, String expected) {
        assertThat(IdDocumentValidator.validate(es, type, input)).isEqualTo(new IdDocumentValidator.IdDocument(type, expected));
    }
    @Test void T_04_01_invalidCheckLetterIsRejected() {
        error(() -> IdDocumentValidator.validate(es, "DNI", "12345678A"), ErrorCode.INVALID_ID_DOCUMENT);
        error(() -> IdDocumentValidator.validate(es, "NIE", "X1234567A"), ErrorCode.INVALID_ID_DOCUMENT);
    }
    @Test void T_04_02_passportRequiresEmptyNationalDocumentAndGenericAllowsFreeForm() {
        assertThat(IdDocumentValidator.spanish(es, "", "pass-123").value()).isEqualTo("PASS123");
        assertThat(IdDocumentValidator.spanish(es, "1234567L", null).value()).isEqualTo("01234567L");
        assertThat(IdDocumentValidator.spanish(es, " x1234567-l ", "").type()).isEqualTo("NIE");
        error(() -> IdDocumentValidator.spanish(es, "12345678Z", "PASS123"), ErrorCode.ID_DOCUMENT_AMBIGUOUS);
        error(() -> IdDocumentValidator.spanish(es, "invalid", "PASS123"), ErrorCode.ID_DOCUMENT_AMBIGUOUS);
        error(() -> IdDocumentValidator.spanish(es, null, null), ErrorCode.INVALID_ID_DOCUMENT);
        for (String value : Arrays.asList(null, "", "1234", "A".repeat(21), "PASS/123")) {
            error(() -> IdDocumentValidator.validate(es, "PASSPORT", value), ErrorCode.INVALID_ID_DOCUMENT);
        }
        assertThat(IdDocumentValidator.validate(generic, "OTHER", "AB-12").value()).isEqualTo("AB12");
        assertThat(IdDocumentValidator.validate(generic, "PASSPORT", "free form").value()).isEqualTo("FREEFORM");
        error(() -> IdDocumentValidator.validate(generic, "OTHER", "AB"), ErrorCode.INVALID_ID_DOCUMENT);
        error(() -> IdDocumentValidator.validate(generic, "DNI", "12345678Z"), ErrorCode.INVALID_ID_DOCUMENT);
    }
    @Test void T_04_03_postalDatasetSupportsZeroOneAndManyTowns() {
        var one = PostalCodeTowns.lookup(es, "08349");
        assertThat(one.selection()).isEqualTo(PostalCodeTowns.Selection.PREFILLED);
        assertThat(PostalCodeTowns.select(one, null)).isEqualTo("Cabrera de Mar");
        var many = PostalCodeTowns.lookup(es, "12345");
        assertThat(many.towns()).hasSize(3);
        assertThat(many.selection()).isEqualTo(PostalCodeTowns.Selection.CHOICE);
        assertThat(PostalCodeTowns.select(many, "")).isEqualTo("Town A");
        assertThat(PostalCodeTowns.select(many, " Town B ")).isEqualTo("Town B");
        error(() -> PostalCodeTowns.select(many, "Town Z"), ErrorCode.VALIDATION_ERROR);
        var none = PostalCodeTowns.lookup(es, "00000");
        assertThat(none.selection()).isEqualTo(PostalCodeTowns.Selection.FREE_TEXT);
        assertThat(none.defaultTown()).isNull();
        assertThat(PostalCodeTowns.select(none, "Free town")).isEqualTo("Free town");
        error(() -> PostalCodeTowns.select(none, " "), ErrorCode.VALIDATION_ERROR);
        assertThat(PostalCodeTowns.lookup(generic, "AB 123").towns()).isEmpty();
        assertThat(PostalCodeTowns.lookup(generic, "08349").towns()).isEmpty();
        for (String invalid : Arrays.asList(null, "12", "12345678901")) {
            error(() -> PostalCodeTowns.lookup(generic, invalid), ErrorCode.VALIDATION_ERROR);
            error(() -> PostalCodeTowns.lookup(es, invalid), ErrorCode.VALIDATION_ERROR);
        }
        // The production E2-T02 dataset is read from the classpath, without Mongo or Spring context.
        assertThat(PostalCodeTowns.lookup(new CountryContactRules(new CountryProfileRegistry().get("ES")), "08349").towns())
                .extracting(CountryContactRules.Town::name).contains("Cabrera de Mar");
    }
    @Test void T_04_04_phonesReuseCountryNormalizationAndRequireSecondLabel() {
        var phones = PhoneNormalizer.normalize(es, List.of(new PhoneNormalizer.Input("+34", "655 12 34 56", null),
                new PhoneNormalizer.Input("", "0034655123457", " Home ")));
        assertThat(phones).extracting(CountryContactRules.Phone::e164).containsExactly("+34655123456", "+34655123457");
        assertThat(phones.get(1).label()).isEqualTo("Home");
        assertThat(es.phone(null, "655123456", null).e164()).isEqualTo("+34655123456");
        assertThat(generic.phone("+1", "202 555 0100", null).e164()).isEqualTo("+12025550100");
        assertThat(generic.phone(null, "+351912345678", null).e164()).isEqualTo("+351912345678");
        error(() -> es.phone("+34", "55123456", null), ErrorCode.INVALID_PHONE);
        error(() -> es.phone("+1", "+34655123456", null), ErrorCode.INVALID_PHONE);
        error(() -> es.phone(null, null, null), ErrorCode.INVALID_PHONE);
        error(() -> generic.phone(null, "2025550100", null), ErrorCode.INVALID_PHONE);
        error(() -> generic.phone(null, "+99912345678", null), ErrorCode.INVALID_PHONE);
        error(() -> es.phone("+34", "655123456", "x".repeat(31)), ErrorCode.VALIDATION_ERROR);
        var first = new PhoneNormalizer.Input("+34", "655123456", null);
        for (String label : Arrays.asList(null, " ")) {
            error(() -> PhoneNormalizer.normalize(es, List.of(first, new PhoneNormalizer.Input("+34", "655123457", label))),
                    ErrorCode.VALIDATION_ERROR);
        }
        error(() -> PhoneNormalizer.normalize(es, null), ErrorCode.VALIDATION_ERROR);
        error(() -> PhoneNormalizer.normalize(es, List.of()), ErrorCode.VALIDATION_ERROR);
        error(() -> PhoneNormalizer.normalize(es, List.of(first, first, first)), ErrorCode.VALIDATION_ERROR);
    }
    @Test void T_04_04_emailsAreNormalizedDistinctAndFirstIsPrimary() {
        assertThat(EmailRules.normalize(List.of("  PAU@example.test ", "other@example.test"))).containsExactly(
                new EmailRules.ContactEmail("pau@example.test", true, false), new EmailRules.ContactEmail("other@example.test", false, false));
        assertThatThrownBy(() -> EmailRules.normalize(List.of("pau@example.test", "PAU@example.test")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details().get("fieldErrors")).isEqualTo(
                        List.of(Map.of("field", "emails[1]", "code", "VALIDATION_ERROR"))));
        error(() -> EmailRules.normalize(null), ErrorCode.VALIDATION_ERROR);
        error(() -> EmailRules.normalize(List.of()), ErrorCode.VALIDATION_ERROR);
        error(() -> EmailRules.normalize(List.of("a", "b", "c")), ErrorCode.VALIDATION_ERROR);
        for (String bad : Arrays.asList(null, "not-an-email", "a@localhost", "a".repeat(255) + "@example.test")) {
            error(() -> EmailRules.normalize(Arrays.asList(bad)), ErrorCode.VALIDATION_ERROR);
        }
    }
    static void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
