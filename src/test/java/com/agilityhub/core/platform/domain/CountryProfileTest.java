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
    @Test void T_02_04_genericIsFallbackWithNoNationalFormatValidation() {
        var generic = registry.get("GENERIC");
        assertThat(registry.get("PT")).isSameAs(generic);
        assertThat(generic.code()).isEqualTo("GENERIC"); assertThat(generic.idDocumentTypes()).containsExactly("OTHER");
        assertThat(generic.defaultPhonePrefix()).isEmpty(); assertThat(generic.dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(generic.validateIdDocument("OTHER", "free form")).isTrue();
        assertThat(generic.validateIban("free form")).isTrue(); assertThat(generic.postalCodeLookup("08349")).isEmpty();
        assertThat(generic.normalizePhone("+351 912 345 678")).isEqualTo("+351912345678");
        for (String value : new String[]{null, "612345678", "+0123", "+1234567890123456", "+1e9"}) {
            assertThatThrownBy(() -> generic.normalizePhone(value)).isInstanceOf(ApiException.class);
        }
    }
}
