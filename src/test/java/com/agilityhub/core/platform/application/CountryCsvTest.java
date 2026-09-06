package com.agilityhub.core.platform.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CountryCsvTest {
    @Test void T_02_04_csvPreservesQuotedCommasAndEscapedQuotes() {
        assertThat(CountryProfileRegistry.csv("08349,\"Example, town\",\"A \"\"quoted\"\" region\""))
                .containsExactly("08349", "Example, town", "A \"quoted\" region");
    }
}
