package com.agilityhub.core.platform.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HostNamesTest {
    @Test void T_02_06_hostsNormalizePortsCaseTrailingDotAndIdn() {
        assertThat(HostNames.normalize("APP.EXAMPLE.TEST.:8080")).isEqualTo("app.example.test");
        assertThat(HostNames.normalize("bücher.example.test")).isEqualTo("xn--bcher-kva.example.test");
        for (String value : new String[]{null, " ", "https://app.example.test", "bad/host", "app.example.test,other"}) {
            assertThat(HostNames.normalize(value)).isEmpty();
        }
    }
}
