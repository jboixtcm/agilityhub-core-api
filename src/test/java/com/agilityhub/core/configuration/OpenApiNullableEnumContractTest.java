package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3-T10 round 2, point 3 (review #3, generalised by the organizer): a nullable schema (`type` lists `null`) with an `enum`
 * lists `null` in the `enum` as well. In JSON Schema 2020-12 the `enum` alone decides which values pass, so a strict
 * validator rejected the `null` that the type allowed (`RiskNotified.gender`, S14 R-14-06, and 13 more).
 */
class OpenApiNullableEnumContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The 14 schemas the organizer found in the snapshot on 25-09. */
    static final List<String> FOUND = List.of("ActivityRegistrationListItem/cancelReason", "Actor/gender", "BookableClass/notBookableReason",
            "CardMember/gender", "HomeMember/gender", "JobRun/skipReason", "JobRunListItem/skipReason", "JobScheduleView/dayOfWeek", "JobSummary/module",
            "RiskNotified/gender", "SlotCell/reason", "TrainingBooking/cancelReason", "TrainingBooking/cancelledBy", "WaitlistEntry/cancelReason");

    static JsonNode snapshot() throws Exception { return MAPPER.readTree(Path.of("docs/openapi/openapi.json").toFile()); }

    @Test void E3_T10_everyNullableEnumOfTheSnapshotListsNull() throws Exception {
        var nullable = new TreeMap<String, JsonNode>(); var problems = new ArrayList<String>();
        walk(snapshot(), "", nullable, problems);
        assertThat(problems).as(String.join("\n", problems)).isEmpty();
        for (String field : FOUND) { assertThat(nullable).as(field).containsKey("/components/schemas/" + field.replace("/", "/properties/")); }
        // Each of them, validated on its own by a JSON Schema 2020-12 validator: `null` and its values pass, anything else fails.
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        nullable.forEach((path, node) -> {
            var schema = factory.getSchema(node);
            assertThat(schema.validate(MAPPER.nullNode())).as(path + " accepts null").isEmpty();
            assertThat(schema.validate(node.path("enum").get(0))).as(path + " accepts " + node.path("enum").get(0)).isEmpty();
            assertThat(schema.validate(MAPPER.getNodeFactory().textNode("NOT_IN_THE_ENUM"))).as(path + " still closes its values").isNotEmpty();
        });
    }

    @Test void T_14_01_aRiskNotifiedRowWithoutGenderValidatesAgainstTheGeneratedSchema() throws Exception {
        var root = MAPPER.createObjectNode().put("$ref", "#/components/schemas/RiskNotified");
        root.set("components", snapshot().path("components"));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        var sample = MAPPER.createObjectNode().put("memberFirstName", "Example").put("dogName", "Example Dog").putNull("gender");
        assertThat(schema.validate(sample)).as("R-14-06: a member without a gender on file").isEmpty();
        assertThat(schema.validate(sample.deepCopy().put("gender", "FEMALE"))).isEmpty();
        assertThat(schema.validate(sample.deepCopy().put("gender", "UNKNOWN"))).isNotEmpty();
        var missing = sample.deepCopy(); missing.remove("gender");
        assertThat(schema.validate(missing)).as("gender is always present, null or a value").isNotEmpty();
    }

    /** The generator itself: the Jackson module that writes the document adds `null`, once, only to nullable enums. */
    @Test void E3_T10_theDocumentWriterAddsNullToTheEnumOfNullableSchemasOnly() throws Exception {
        var model = new Schema<>().types(new LinkedHashSet<>(List.of("object")));
        model.addProperty("nullable", enumSchema(List.of("string", "null"), "A", "B"));
        model.addProperty("closed", enumSchema(List.of("string"), "A", "B"));
        model.addProperty("alreadyNull", enumSchema(List.of("string", "null"), "A", null));
        model.addProperty("free", new Schema<>().types(new LinkedHashSet<>(List.of("string", "null"))));
        var mapper = Json31.mapper().copy().registerModule(OpenApiConfiguration.nullableEnumsModule());
        var written = mapper.readTree(mapper.writeValueAsString(model)).path("properties");
        assertThat(written.at("/nullable/enum").toString()).isEqualTo("[\"A\",\"B\",null]");
        assertThat(written.at("/closed/enum").toString()).isEqualTo("[\"A\",\"B\"]");
        assertThat(written.at("/alreadyNull/enum").toString()).isEqualTo("[\"A\",null]");
        assertThat(written.path("free").has("enum")).isFalse();
        // Without the module the writer keeps the enum as the model has it: the defect this test guards.
        var plain = Json31.mapper().readTree(Json31.mapper().writeValueAsString(model)).path("properties");
        assertThat(plain.at("/nullable/enum").toString()).isEqualTo("[\"A\",\"B\"]");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Schema<?> enumSchema(List<String> types, String... values) {
        Schema schema = new Schema<>().types(new LinkedHashSet<>(types));
        schema.setEnum(new ArrayList<>(Arrays.asList(values)));
        return schema;
    }

    private static void walk(JsonNode node, String path, Map<String, JsonNode> nullable, List<String> problems) {
        if (node.isObject()) {
            if (node.path("enum").isArray() && types(node.path("type")).contains("null")) {
                nullable.put(path, node);
                boolean listsNull = false;
                for (var value : node.path("enum")) { listsNull |= value.isNull(); }
                if (!listsNull) { problems.add(path + " allows null in its type but its enum " + node.path("enum") + " does not"); }
            }
            node.fields().forEachRemaining(entry -> walk(entry.getValue(), path + "/" + entry.getKey(), nullable, problems));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) { walk(node.get(i), path + "/" + i, nullable, problems); }
        }
    }
    private static Set<String> types(JsonNode type) {
        var result = new HashSet<String>();
        if (type.isTextual()) { result.add(type.asText()); }
        type.forEach(value -> { if (value.isTextual()) { result.add(value.asText()); } });
        return result;
    }
}
