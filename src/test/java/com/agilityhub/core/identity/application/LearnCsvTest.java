package com.agilityhub.core.identity.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LearnCsvTest {
    @Test void T_01_14_csvSupportsBomCrLfQuotedNamesEscapedQuotesAndMultilineFields() {
        assertThat(LearnCsv.read("\uFEFFid,name\r\n1,\"Example, \"\"One\"\"\"\r\n2,\"Two\nLines\"\n3,End"))
                .containsExactly(List.of("id", "name"), List.of("1", "Example, \"One\""), List.of("2", "Two\nLines"), List.of("3", "End"));
        assertThat(LearnCsv.read("a,\r\nb,\"\"\rc,"))
                .containsExactly(List.of("a", ""), List.of("b", ""), List.of("c", ""));
        assertThat(LearnCsv.read("")).isEmpty();
    }
    @Test void T_01_14_csvRejectsBrokenQuotingWithoutEchoingInput() {
        for (String text : List.of("a,\"private", "a,\"private\"x", "a,private\"")) {
            assertThatThrownBy(() -> LearnCsv.read(text)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
        }
    }
}
