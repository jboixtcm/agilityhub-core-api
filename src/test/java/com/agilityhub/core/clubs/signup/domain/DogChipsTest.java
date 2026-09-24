package com.agilityhub.core.clubs.signup.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** S04 §3 `Dog.chip` per country profile, normalised before any check (R-04-07; E3-T08, M21). */
class DogChipsTest {
    @Test void T_04_07_chipIsNormalisedBeforeTheProfileCheck() {
        assertThat(DogChips.normalize(" 941 000-000 000 001 ")).isEqualTo("941000000000001");
        assertThat(DogChips.normalize("ab-12 cd34")).isEqualTo("AB12CD34");
        assertThat(DogChips.normalize(null)).isNull();
    }
    @Test void T_04_07_spanishChipsHaveFifteenDigits() {
        assertThat(DogChips.valid("ES", "941000000000001")).isTrue();
        assertThat(DogChips.valid("ES", "94100000000000")).isFalse();
        assertThat(DogChips.valid("ES", "9410000000000011")).isFalse();
        assertThat(DogChips.valid("ES", "94100000000000A")).isFalse();
        assertThat(DogChips.valid("ES", null)).isFalse();
    }
    @Test void T_04_07_genericChipsHaveEightToFifteenAlphanumerics() {
        assertThat(DogChips.valid("GENERIC", "AB12CD34")).isTrue();
        assertThat(DogChips.valid("GENERIC", "ABCDEFGHIJ12345")).isTrue();
        assertThat(DogChips.valid("GENERIC", "AB12CD3")).isFalse();
        assertThat(DogChips.valid("GENERIC", "ABCDEFGHIJ123456")).isFalse();
        assertThat(DogChips.valid("GENERIC", "AB12-CD34")).isFalse();
    }
}
