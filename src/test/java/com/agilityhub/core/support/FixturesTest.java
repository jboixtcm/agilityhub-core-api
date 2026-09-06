package com.agilityhub.core.support;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixturesTest {

    private final Fixtures fixtures = new Fixtures(JsonMapper.builder().build());

    @Test
    void E0_T02_loadsTypedJsonFixtures() {
        var list = fixtures.readList("fixtures/transaction-smoke.json", FixtureDocument.class);
        var array = fixtures.read("fixtures/transaction-smoke.json", FixtureDocument[].class);
        assertThat(list).containsExactly(
                new FixtureDocument("first", "First transaction fixture"),
                new FixtureDocument("second", "Second transaction fixture"));
        assertThat(array).containsExactlyElementsOf(list);
    }

    @Test
    void E0_T02_missingFixtureReportsItsPath() {
        assertThatThrownBy(() -> fixtures.read("fixtures/missing.json", FixtureDocument.class))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Cannot load JSON fixture: fixtures/missing.json");
    }

    record FixtureDocument(String id, String value) {
    }
}
