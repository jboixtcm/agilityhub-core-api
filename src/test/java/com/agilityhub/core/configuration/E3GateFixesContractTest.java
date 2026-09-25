package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3-T16: the additive contract of the gate fixes, read from the committed snapshot (which {@code OpenApiSnapshotTest}
 * keeps equal to the generated document): the member's document types on `GET /me/dogs`, the public footer's town and
 * registered office on `/branding` with the club's `displayCity`, and the four list page sizes.
 */
class E3GateFixesContractTest {
    static JsonNode document;
    @BeforeAll static void snapshot() throws Exception { document = new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile()); }
    static JsonNode schema(String name) { return document.at("/components/schemas/" + name); }
    static List<String> texts(JsonNode node) {
        var values = new ArrayList<String>();
        if (node.isArray()) { node.forEach(value -> values.add(value.asText())); } else if (!node.isMissingNode()) { values.add(node.asText()); }
        return values;
    }

    /** Step 1 (R-03-15, R-03-32; S03 §6 amended 25-09): `documentTypes: [{key, label, required}]`, always present. */
    @Test void T_03_26_meDogsAlwaysCarriesTheClubsDocumentTypes() {
        assertThat(texts(schema("MeDogs").path("required"))).containsExactlyInAnyOrder("dogs", "canAddDog", "documentTypes");
        var types = schema("MeDogs").at("/properties/documentTypes");
        assertThat(types.path("type").asText()).isEqualTo("array");
        assertThat(types.at("/items/$ref").asText()).isEqualTo("#/components/schemas/DogDocumentType");
        var type = schema("DogDocumentType");
        assertThat(texts(type.path("required"))).containsExactlyInAnyOrder("key", "label", "required");
        assertThat(type.at("/properties/key/type").asText()).isEqualTo("string");
        assertThat(type.at("/properties/label/type").asText()).isEqualTo("string");
        assertThat(type.at("/properties/required/type").asText()).isEqualTo("boolean");
    }

    /**
     * Step 3 (R-02-02, S02 §3, amended 25-09): `club.city` = `displayCity ?? address.city` and `club.legalAddress` (the
     * registered office, or null) are always present on `/branding`; `displayCity` is read and edited with the club settings.
     */
    @Test void T_02_07_brandingNamesTheTownAndTheRegisteredOfficeAndTheClubSettingsCarryDisplayCity() {
        var summary = schema("ClubSummary");
        assertThat(texts(summary.path("required"))).containsExactlyInAnyOrder("slug", "name", "city", "legalName", "taxId", "legalAddress");
        assertThat(texts(summary.at("/properties/city/type"))).containsExactlyInAnyOrder("string", "null");
        // The house form of a required, nullable object (as D1's `riskReview`): the reference, with `null` among its types.
        var office = summary.at("/properties/legalAddress");
        assertThat(office.path("$ref").asText()).isEqualTo("#/components/schemas/LegalAddress");
        assertThat(texts(office.path("type"))).containsExactlyInAnyOrder("object", "null");
        var address = schema("LegalAddress");
        assertThat(texts(address.path("required"))).containsExactlyInAnyOrder("street", "postalCode", "city");
        assertThat(address.at("/properties/street/type").asText()).isEqualTo("string");
        assertThat(address.at("/properties/postalCode/type").asText()).isEqualTo("string");
        assertThat(texts(address.at("/properties/city/type"))).containsExactlyInAnyOrder("string", "null");
        for (String settings : List.of("ClubSettings", "ClubUpdate")) {
            assertThat(schema(settings).at("/properties/displayCity/type").asText()).as(settings).isEqualTo("string");
            assertThat(texts(schema(settings).path("required"))).as(settings).doesNotContain("displayCity");
            assertThat(schema(settings).at("/properties/taxId").has("format")).as(settings + ": a tax id is no uuid").isFalse();
        }
    }

    /** Step 4 (CONVENCIONS_API §4, amended 25-09): the shared `size` parameter lists the four page sizes, default 50. */
    @Test void T_03_08_everyListDocumentsTheFourPageSizes() {
        var sizes = new ArrayList<JsonNode>();
        document.path("paths").forEach(operations -> operations.forEach(operation -> operation.path("parameters").forEach(parameter -> {
            if ("size".equals(parameter.path("name").asText()) && parameter.path("description").asText().startsWith("Requested page size")) { sizes.add(parameter); }
        })));
        assertThat(sizes).hasSizeGreaterThanOrEqualTo(29).allSatisfy(parameter -> {
            assertThat(parameter.path("in").asText()).isEqualTo("query");
            assertThat(texts(parameter.at("/schema/enum"))).containsExactly("20", "50", "200", "1000");
            assertThat(parameter.at("/schema/default").asInt()).isEqualTo(50);
            assertThat(parameter.at("/schema/type").asText()).isEqualTo("integer");
            assertThat(parameter.at("/schema").has("minimum")).isFalse();
        });
    }
}
