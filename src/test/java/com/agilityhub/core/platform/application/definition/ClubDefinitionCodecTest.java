package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.ModuleDependencyValidator;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ClubDefinitionCodecTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ClubDefinitionCodec codec = new ClubDefinitionCodec(mapper, new ModuleDependencyValidator());
    ClubDefinitionCodecTest() throws Exception { }
    ObjectNode seed() { return codec.read(Path.of("seeds/club-canic.yaml")); }
    void fails(ObjectNode input, ErrorCode code) {
        assertThatThrownBy(() -> codec.validate(input)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
    @Test void T_17_01_allThreeSeedsValidateAndRoundTripAsYaml(@TempDir Path temporary) throws Exception {
        for (String seed : new String[]{"canic", "minim", "template-default"}) {
            var definition = codec.read(Path.of("seeds/club-" + seed + ".yaml"));
            var file = temporary.resolve(seed + ".yaml"); java.nio.file.Files.writeString(file, codec.write(definition));
            assertThat(codec.read(file)).isEqualTo(definition);
            assertThat(definition.path("modules").isArray()).isTrue();
        }
    }
    @Test void T_17_01_invalidShapeUnknownPropertiesAndModuleDependenciesAreRejected() {
        var definition = seed(); definition.put("invented", true); fails(definition, ErrorCode.VALIDATION_ERROR);
        definition = seed(); definition.putArray("modules").add("PACKS"); fails(definition, ErrorCode.MODULE_DEPENDENCY);
        definition = seed(); definition.putArray("modules").add("UNKNOWN"); fails(definition, ErrorCode.VALIDATION_ERROR);
        definition = seed(); definition.putObject("paymentProviders").put("secretKey", "fictional"); fails(definition, ErrorCode.VALIDATION_ERROR);
        definition = seed(); definition.withObject("catalogs").putArray("levels").addObject().put("name", "bad shape"); fails(definition, ErrorCode.VALIDATION_ERROR);
        definition = seed(); definition.put("preset", "MINIM"); fails(definition, ErrorCode.VALIDATION_ERROR);
        definition = seed(); definition.withObject("club").put("template", true); fails(definition, ErrorCode.VALIDATION_ERROR);
        for (String field : new String[]{"timeZone", "currency", "defaultLocale"}) {
            definition = seed(); definition.withObject("club").put(field, field.equals("defaultLocale") ? "en" : "ZZZ");
            fails(definition, ErrorCode.VALIDATION_ERROR);
        }
    }
    @Test void T_17_02_normalizesHostsRejectsDuplicatesAndReservedHosts() {
        var definition = seed(); ((ObjectNode) definition.path("domains").get(0)).put("host", "APP.AGILITYCANIC.CAT.").remove("app");
        assertThat(codec.validate(definition).path("domains").get(0).path("host").asText()).isEqualTo("app.agilitycanic.cat");
        ((ObjectNode) definition.path("domains").get(1)).put("host", "app.agilitycanic.cat"); fails(definition, ErrorCode.VALIDATION_ERROR);
        for (String host : new String[]{"core.example.test", "id.example.test", "clubs.example.test", "clubsadmin.example.test"}) {
            definition = seed(); ((ObjectNode) definition.path("domains").get(0)).put("host", host); fails(definition, ErrorCode.HOST_RESERVED);
        }
        definition = seed(); ((ObjectNode) definition.path("domains").get(0)).put("host", "-invalid.test"); fails(definition, ErrorCode.VALIDATION_ERROR);
    }
    @Test void T_17_01_duplicateAdminsAndMalformedYamlAreRejected(@TempDir Path temporary) throws Exception {
        var definition = seed(); ((com.fasterxml.jackson.databind.node.ArrayNode) definition.path("admins")).add(definition.path("admins").get(0).deepCopy());
        fails(definition, ErrorCode.VALIDATION_ERROR);
        for (String yaml : new String[]{"club: [broken", "club: 1\nclub: 2", "!!java.net.URL ['https://example.test']"}) {
            var path = temporary.resolve("invalid.yaml"); java.nio.file.Files.writeString(path, yaml);
            assertThatThrownBy(() -> codec.read(path)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> codec.read(temporary.resolve("absent"))).isInstanceOf(ApiException.class);
    }
}
