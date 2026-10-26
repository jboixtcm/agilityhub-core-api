package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.ModuleDependencyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DemoClubDefinitionTest {
    final ClubDefinitionCodec codec = new ClubDefinitionCodec(new ObjectMapper(), new ModuleDependencyValidator());
    DemoClubDefinitionTest() throws java.io.IOException { }

    @Test void T_17_01_INC94_demoHasIndependentIdentityAndNonoperativePaymentInstructions() throws Exception {
        var production = codec.read(Path.of("seeds/club-canic.yaml"));
        var demo = codec.read(Path.of("seeds/club-canic-demo.yaml"));
        assertThat(demo.at("/club/name").asText()).startsWith("Demo ");
        assertThat(demo.at("/club/taxId").isMissingNode()).isTrue();
        assertThat(demo.at("/club/contactEmail").asText()).isEqualTo("demo@example.test");
        assertThat(demo.at("/club/locales").toString()).isEqualTo("[\"ca\",\"es\",\"en\"]");
        for (var domain : demo.path("domains")) { assertThat(domain.path("host").asText()).endsWith(".example.test"); }
        var instructions = demo.at("/paymentProviders/MANUAL/instructions");
        assertThat(instructions.fieldNames()).toIterable().containsExactly("ca", "es", "en");
        assertThat(instructions.path("ca").asText()).contains("No facis cap pagament real");
        assertThat(instructions.path("es").asText()).contains("No hagas ningún pago real");
        assertThat(instructions.path("en").asText()).contains("Do not make any real payment");
        assertThat(demo.toString()).doesNotContain("agilitycanic.cat", production.at("/club/taxId").asText(),
                production.at("/club/address/street").asText()).doesNotMatch("(?s).*ES\\d{2}[\\d ]{20,}.*");
        for (String field : List.of("accounts", "catalogs", "parameters")) {
            assertThat(demo.path(field)).as("demo retains functional fixture " + field).isEqualTo(production.path(field));
        }
        var modules = new java.util.TreeSet<String>(); production.path("modules").forEach(value -> modules.add(value.asText()));
        modules.remove("LEARN_LINK");
        var demoModules = new java.util.TreeSet<String>(); demo.path("modules").forEach(value -> demoModules.add(value.asText()));
        assertThat(demoModules).isEqualTo(modules);
        assertThat(Files.readString(Path.of("seeds/club-canic-demo.yaml"))).doesNotContain("607 475 945");
    }
}
