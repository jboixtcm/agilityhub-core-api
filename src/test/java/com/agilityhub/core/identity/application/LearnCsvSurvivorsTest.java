package com.agilityhub.core.identity.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link LearnCsv#read} (S01 R-01-12, T-01-14): a file whose last field is quoted, or whose last line
 * ends with a bare CR, is read to its end without looking past the last character.
 */
class LearnCsvSurvivorsTest {

    @Test void T_01_14_aFileEndingInAClosingQuoteIsRead() {
        assertThat(LearnCsv.read("id,name\n1,\"Laura Example\""))
                .containsExactly(List.of("id", "name"), List.of("1", "Laura Example"));
    }

    @Test void T_01_14_aFileEndingInABareCarriageReturnIsRead() {
        assertThat(LearnCsv.read("id,name\r1,Laura Example\r"))
                .containsExactly(List.of("id", "name"), List.of("1", "Laura Example"));
    }
}
