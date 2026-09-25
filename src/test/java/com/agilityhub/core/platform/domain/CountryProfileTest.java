package com.agilityhub.core.platform.domain;

import com.agilityhub.core.platform.application.CountryProfileRegistry;
import com.agilityhub.core.shared.domain.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CountryProfileTest {
    final CountryProfileRegistry registry = new CountryProfileRegistry();
    @Test void T_02_04_spanishIdChecksPostalLookupPhonesAndIban() {
        var es = registry.get("ES");
        assertThat(es.code()).isEqualTo("ES"); assertThat(es.idDocumentTypes()).containsExactly("DNI", "NIE", "PASSPORT");
        assertThat(es.validateIdDocument("DNI", "12345678Z")).isTrue();
        assertThat(es.validateIdDocument("DNI", "12345678A")).isFalse();
        assertThat(es.validateIdDocument("NIE", "X1234567L")).isTrue();
        assertThat(es.validateIdDocument("NIE", "Y1234567X")).isTrue();
        assertThat(es.validateIdDocument("NIE", "Z1234567R")).isTrue();
        assertThat(es.validateIdDocument("NIE", "X1234567A")).isFalse();
        assertThat(es.validateIdDocument("PASSPORT", "EXAMPLE")).isTrue();
        assertThat(es.validateIdDocument("DNI", null)).isFalse(); assertThat(es.validateIdDocument("DNI", " ")).isFalse();
        assertThat(es.validateIdDocument("DNI", "123")).isFalse(); assertThat(es.validateIdDocument("NIE", "123")).isFalse();
        assertThat(es.validateIdDocument("OTHER", "example")).isFalse();
        assertThat(es.postalCodeLookup("08349")).extracting(CountryProfile.Town::town).contains("Cabrera de Mar");
        assertThat(es.postalCodeLookup("28001")).isNotEmpty(); assertThat(es.postalCodeLookup("00000")).isEmpty();
        assertThat(es.normalizePhone("612345678")).isEqualTo("+34612345678");
        assertThat(es.normalizePhone("00 34 612 345 678")).isEqualTo("+34612345678");
        assertThat(es.normalizePhone("+1 (202) 555-0100")).isEqualTo("+12025550100");
        assertThatThrownBy(() -> es.normalizePhone("+34612")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> es.normalizePhone(null)).isInstanceOf(ApiException.class);
        assertThat(es.validateIban("ES91 2100 0418 4502 0005 1332")).isTrue();
        assertThat(es.validateIban("DE89370400440532013000")).isTrue();
        assertThat(es.validateIban("GB82WEST12345698765432")).isTrue();
        assertThat(es.validateIban("ES0021000418450200051332")).isFalse();
        assertThat(es.validateIban("ES912100041845020005133200")).isFalse();
        assertThat(es.validateIban(null)).isFalse(); assertThat(es.validateIban("XX1234")).isFalse();
        assertThat(es.dateFormat()).isEqualTo("dd/MM/yyyy"); assertThat(es.timeFormat()).isEqualTo("HH:mm");
    }
    /**
     * E3-T16 step 3 (S02 §3): the club's tax id; the Cànic's CIF G63189617 (Jordi, 25-09) passes the Spanish profile. Round 2
     * (S02 §2, R-02-06, T-02-04): `GENERIC` has no tax id validation, so it accepts any value.
     */
    @Test void T_02_04_spanishTaxIdsAreACifNifOrNieWithTheirCheckCharacterAndGenericOnesAreNotValidated() {
        var es = registry.get("ES");
        for (String valid : new String[]{"G63189617", "g-6318 9617", "B12345674", "Q0000000J", "P0800000B", "S2800000H", "G0000000J", "12345678Z", "X1234567L"}) {
            assertThat(es.validateTaxId(valid)).as(valid).isTrue();
        }
        // A wrong control; a letter where A/B/E/H take a digit; a digit where K–W take a letter; not a tax id at all.
        for (String invalid : new String[]{"G63189618", "B1234567D", "Q00000000", "S2800000E", "I12345674", "G6318961", "G631896177", "12345678A", "EXAMPLE-ORG", "", " ", null}) {
            assertThat(es.validateTaxId(invalid)).as(String.valueOf(invalid)).isFalse();
        }
        var generic = registry.get("GENERIC");
        for (String any : new String[]{"AB1", "ÀÉÍ-ÒÚ", "PT 501.234.567", "EXAMPLE-ORG", "G63189618", "x".repeat(41), ""}) {
            assertThat(generic.validateTaxId(any)).as(any).isTrue();
        }
    }
    @Test void T_02_04_genericIsFallbackWithNoNationalFormatValidation() {
        var generic = registry.get("GENERIC");
        assertThat(registry.get("PT")).isSameAs(generic);
        assertThat(generic.code()).isEqualTo("GENERIC"); assertThat(generic.idDocumentTypes()).containsExactly("PASSPORT", "OTHER");
        assertThat(generic.defaultPhonePrefix()).isEmpty(); assertThat(generic.dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(generic.validateIdDocument("OTHER", "free form")).isTrue();
        assertThat(generic.postalCodeLookup("08349")).isEmpty();
        assertThat(generic.normalizePhone("+351 912 345 678")).isEqualTo("+351912345678");
        for (String value : new String[]{null, "612345678", "+0123", "+1234567890123456", "+1e9"}) {
            assertThatThrownBy(() -> generic.normalizePhone(value)).isInstanceOf(ApiException.class);
        }
    }
    /** E3-T10 step 8 (R-04-10): GENERIC used to accept any text; it runs the mod-97 check on the ISO 13616 shape. */
    @Test void T_04_14_genericIbanRunsTheMod97Check() {
        var generic = registry.get("GENERIC");
        for (String valid : new String[]{"PT50 0002 0123 1234 5678 9015 4", "DE89370400440532013000", "gb82 west 1234 5698 7654 32", "ES9121000418450200051332"}) {
            assertThat(generic.validateIban(valid)).as(valid).isTrue();
        }
        for (String invalid : new String[]{null, "", "free form", "PT51000201231234567890154", "DE88370400440532013000", "XX1234", "DE89 3704 0044 0532 0130 00 AB CD EF GH IJ KL MN"}) {
            assertThat(generic.validateIban(invalid)).as(String.valueOf(invalid)).isFalse();
        }
    }
}
