package com.agilityhub.core.shared.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E11-T15 round 2 (the kit's review 4; S12 R-12-12 `CdtrSchmeId`, S17 R-17-05): ISO 7064 mod 97-10 yields check digits 02–98
 * only. «00» and «01» leave the same remainder as their canonical 97 and 98, and a bank refuses them: only the canonical digits
 * are valid.
 */
class SepaCreditorIdentifierTest {
    @Test void R_12_12_R_17_05_onlyTheCanonicalCheckDigitsAreValid() {
        for (String alias : List.of("ES00ZZZB12345678", "ES01ZZZG00000000", "ES99ZZZG00000000", "ES00000G12345678")) {
            assertThat(SepaCreditorIdentifier.valid(alias)).as(alias).isFalse();
        }
        for (String canonical : List.of("ES97ZZZB12345678", "ES26000G12345678", "ES10ZZZG00000000", "ES81ZZZB00000000", "ES64ZZZ999999999",
                "ES10001G00000000", "ES34000X0000000X", "es26 zzz g12345678")) {
            assertThat(SepaCreditorIdentifier.valid(canonical)).as(canonical).isTrue();
        }
        for (String malformed : List.of("", "G12345678", "ESAAZZZB12345678", "ES26ZZZ")) {
            assertThat(SepaCreditorIdentifier.valid(malformed)).as(malformed).isFalse();
        }
        assertThat(SepaCreditorIdentifier.valid(null)).isFalse();
    }
}
