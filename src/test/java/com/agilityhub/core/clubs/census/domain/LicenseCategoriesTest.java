package com.agilityhub.core.clubs.census.domain;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LicenseCategoriesTest {
    @Test void T_03_32_initialCategoryIsIndependentAndNeverOverwritesAnExplicitValue() {
        Map<String, Object> rsce = Map.of("organisation", "RSCE", "number", "1", "category", "S");
        Map<String, Object> fcag = Map.of("organisation", "fcag", "number", "2", "division", "1D");
        var initial = LicenseCategories.initialize(List.of(fcag, rsce));
        assertThat(initial.getFirst()).containsEntry("category", "S").containsEntry("division", "1D");
        assertThat(fcag).doesNotContainKey("category");
        assertThat(LicenseCategories.initialize(List.of(initial.getFirst(), Map.of("organisation", "RSCE", "category", "L"))).getFirst())
                .containsEntry("category", "S");
        assertThat(LicenseCategories.initialize(List.of(Map.of("organisation", "FCAG", "category", ""), rsce)).getFirst())
                .containsEntry("category", "");
        assertThat(LicenseCategories.initialize(List.of(fcag))).isEqualTo(List.of(fcag));
        assertThat(LicenseCategories.initialize(List.of(fcag, Map.of("organisation", "RSCE", "category", "")))).containsExactly(fcag, Map.of("organisation", "RSCE", "category", ""));
        assertThat(LicenseCategories.initialize(List.of(Map.of("organisation", "OTHER"), rsce)).getFirst()).doesNotContainKey("category");
    }
}
