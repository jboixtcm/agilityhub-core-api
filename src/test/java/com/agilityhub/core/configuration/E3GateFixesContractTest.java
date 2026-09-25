package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.api.SignupResponses;
import com.agilityhub.core.clubs.common.api.DashboardContracts;
import com.agilityhub.core.clubs.training.api.TrainingRequests;
import com.agilityhub.core.platform.api.SettingsContracts;
import com.agilityhub.core.support.SnapshotSchemas;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3-T16: the additive contract of the gate fixes, read from the committed snapshot (which {@code OpenApiSnapshotTest}
 * keeps equal to the generated document): the member's document types on `GET /me/dogs`, the public footer's town and
 * registered office on `/branding` with the club's `displayCity`, and the four list page sizes. Round 2: the nulls the
 * D2 view and the signup results send. E5-T16: the same check on `GET /club`, the dashboard and the training booking
 * request, and on the whole snapshot for a `$ref` with a type beside it.
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
     * registered office, or null) are always present on `/branding`; `displayCity` is read and edited with the club settings,
     * and may be `null`.
     */
    @Test void T_02_07_brandingNamesTheTownAndTheRegisteredOfficeAndTheClubSettingsCarryDisplayCity() {
        var summary = schema("ClubSummary");
        assertThat(texts(summary.path("required"))).containsExactlyInAnyOrder("slug", "name", "city", "legalName", "taxId", "legalAddress");
        assertThat(texts(summary.at("/properties/city/type"))).containsExactlyInAnyOrder("string", "null");
        // Round 2: a required, nullable object is the OpenAPI 3.1 union (as E4 and E5 publish them). `null` among the types
        // beside the `$ref` (round 1) still had to match the `$ref`, so a JSON Schema 2020-12 validator refused `null`.
        var office = summary.at("/properties/legalAddress");
        assertThat(office.has("$ref")).isFalse();
        assertThat(office.at("/anyOf/0/$ref").asText()).isEqualTo("#/components/schemas/LegalAddress");
        assertThat(texts(office.at("/anyOf/1/type"))).containsExactly("null");
        assertThat(office.path("anyOf")).hasSize(2);
        var address = schema("LegalAddress");
        assertThat(texts(address.path("required"))).containsExactlyInAnyOrder("street", "postalCode", "city");
        assertThat(address.at("/properties/street/type").asText()).isEqualTo("string");
        assertThat(address.at("/properties/postalCode/type").asText()).isEqualTo("string");
        assertThat(texts(address.at("/properties/city/type"))).containsExactlyInAnyOrder("string", "null");
        // Round 2 (review #3): `GET /club` sends `displayCity: null` without one, and `PUT /club` clears it with `null`.
        for (String settings : List.of("ClubSettings", "ClubUpdate")) {
            assertThat(texts(schema(settings).at("/properties/displayCity/type"))).as(settings).containsExactlyInAnyOrder("string", "null");
            assertThat(texts(schema(settings).path("required"))).as(settings).doesNotContain("displayCity");
            assertThat(schema(settings).at("/properties/taxId").has("format")).as(settings + ": a tax id is no uuid").isFalse();
        }
    }

    /**
     * Round 2, point 5 (web E3-W08 round 2), from the Java side: an optional property whose record serializes it even when
     * absent (no `@JsonInclude(NON_NULL)`) is sent as `null`, so its schema declares `null`. Every record the D2 view (R-04-19)
     * and the two submission results (R-04-25, R-04-26) reach is checked, including the branches
     * {@code SignupPaymentMethodsIT}'s real responses do not take (a level without a colour, an erased member, a holder's dog
     * without a level…). E5-T17 (review E5-T16 #2): named after INC-08, the incidence it asserts; R-04-19 is the D2 edit.
     */
    @Test void INC_08_everyOptionalPropertyTheSignupViewsSendAsNullIsNullable() {
        var schemas = reached(SignupResponses.MemberSignupView.class, SignupResponses.SignupResult.class, SignupResponses.AddDogSignupResult.class);
        assertThat(schemas.values()).contains("Member", "PaymentMethodView", "Address", "Phone", "BookingBlock", "ImageRights", "DisplayStatus",
                "PlanReference", "LevelSummary", "FamilyMember", "FamilyDog", "SignupReadmission", "ReadmissionValues", "SignupUpfront");
        assertThat(undeclaredNulls(schemas)).as("optional properties sent as null that the snapshot does not declare nullable").isEmpty();
    }

    /**
     * E5-T16 step 1 (review E3-T16 #1; S02 R-02-12, INC-08): `GET /club` sends `legalName`, `taxId`, `address`,
     * `contactEmail`, `contactPhone`, `websiteUrl`, `pwa`, `legal` and `lastChange` as `null` when unset, and so do the
     * records it reaches (a pending domain's `verifiedAt`, a theme without a logo, an address without a street…).
     * E5-T17 (review E5-T16 #2): named after INC-08, the incidence it asserts; R-02-12 is the endpoint's tenant and roles.
     */
    @Test void INC_08_everyOptionalPropertyGetClubSendsAsNullIsNullable() {
        var schemas = reached(SettingsContracts.ClubSettings.class);
        assertThat(schemas.values()).contains("ClubSettings", "ClubAddress", "ClubDomain", "Theme", "Colors", "ClubPwa", "PaymentProviderSummary",
                "ClubLegal", "LastChange");
        assertThat(undeclaredNulls(schemas)).as("optional properties sent as null that the snapshot does not declare nullable").isEmpty();
        var settings = schema("ClubSettings");
        for (String field : List.of("address", "pwa", "legal", "lastChange")) {
            assertThat(settings.at("/properties/" + field + "/anyOf/0/$ref").asText()).as(field).startsWith("#/components/schemas/");
            assertThat(texts(settings.at("/properties/" + field + "/anyOf/1/type"))).as(field).containsExactly("null");
        }
    }

    /**
     * E5-T16 step 1 (review E3-T16 #1; S14 R-14-04, R-14-07, §6): the dashboard's module- and parameter-controlled blocks are
     * `null` when off (`riskReview`, `pendingSignups`, `dogsByLevel` and the four KPIs). Each is published as the union
     * `anyOf [$ref, null]`, not as the `$ref` with `type: [object, null]` that no value satisfied.
     */
    @Test void R_14_04_R_14_07_everyNullBlockOfTheDashboardIsANullableUnion() {
        var schemas = reached(DashboardContracts.Dashboard.class);
        assertThat(schemas.values()).contains("Dashboard", "DashboardKpis", "RiskReview", "PendingSignups", "DogsByLevel", "ClassOccupancyKpi");
        assertThat(undeclaredNulls(schemas)).as("optional properties sent as null that the snapshot does not declare nullable").isEmpty();
        var blocks = Map.of("Dashboard", List.of("riskReview", "pendingSignups", "dogsByLevel"),
                "DashboardKpis", List.of("activeMembers", "classOccupancy", "trainingBookings", "pendingSignups"));
        blocks.forEach((name, fields) -> {
            assertThat(texts(schema(name).path("required"))).as(name).containsAll(fields);
            for (String field : fields) {
                var property = schema(name).at("/properties/" + field);
                assertThat(property.has("$ref") || property.has("type")).as(name + "." + field).isFalse();
                assertThat(property.at("/anyOf/0/$ref").asText()).as(name + "." + field).startsWith("#/components/schemas/");
                assertThat(texts(property.at("/anyOf/1/type"))).as(name + "." + field).containsExactly("null");
            }
        });
    }

    /**
     * E5-T16 step 1 (review E3-T16 #1; S09 R-09-16): the impersonation `override` of a training booking request is absent,
     * `null` or a `TrainingOverride`. Swagger's `$ref` beside `type: "null"` accepted neither value.
     */
    @Test void R_09_16_theTrainingOverrideIsAnOverrideOrNull() {
        assertThat(undeclaredNulls(reached(TrainingRequests.TrainingBookingRequest.class))).isEmpty();
        var request = new ObjectMapper().createObjectNode().put("dogId", "dog-1").put("startsAt", "2030-01-07T09:00:00Z");
        assertThat(SnapshotSchemas.violations(request, "TrainingBookingRequest")).as("without an override").isEmpty();
        request.putNull("override");
        assertThat(SnapshotSchemas.violations(request, "TrainingBookingRequest")).as("override: null").isEmpty();
        request.putObject("override").put("limit", true).put("reason", "Example reason");
        assertThat(SnapshotSchemas.violations(request, "TrainingBookingRequest")).as("an override").isEmpty();
        request.putObject("override").put("reason", "Example reason");
        assertThat(SnapshotSchemas.violations(request, "TrainingBookingRequest")).as("an override without its limit").isNotEmpty();
    }

    /**
     * E5-T16 step 1 (INC-08): no property of the whole snapshot is a `$ref` with a `type` beside it. A JSON Schema 2020-12
     * validator checks both, so the `null` such a type allows still fails the `$ref`; the union is the standard form.
     */
    @Test void INC_08_noPropertyOfTheSnapshotIsAReferenceWithATypeBesideIt() {
        var found = new ArrayList<String>();
        walk(document, "", found);
        assertThat(found).isEmpty();
    }
    static void walk(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            if (node.has("$ref") && node.has("type")) { found.add(path); }
            node.fields().forEachRemaining(field -> walk(field.getValue(), path + "/" + field.getKey(), found));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) { walk(node.get(index), path + "/" + index, found); }
        }
    }

    /** The records the roots reach, with their schema names. */
    static Map<Class<?>, String> reached(Class<?>... roots) {
        var schemas = new LinkedHashMap<Class<?>, String>();
        for (Class<?> root : roots) { reach(root, schemas); }
        return schemas;
    }
    /**
     * The optional properties of these records that Jackson writes as `null` but the snapshot does not declare nullable, and
     * every `$ref` with a type beside it (a nullable reference is a union: such a type would still have to match the `$ref`).
     */
    static List<String> undeclaredNulls(Map<Class<?>, String> schemas) {
        var undeclared = new ArrayList<String>();
        schemas.forEach((type, name) -> {
            var schema = schema(name);
            assertThat(schema.path("properties").isObject()).as(name).isTrue();
            var required = texts(schema.path("required"));
            for (var component : type.getRecordComponents()) {
                if (component.getType().isPrimitive() || required.contains(component.getName()) || !sentAsNull(type, component)) { continue; }
                if (!allowsNull(schema.at("/properties/" + component.getName()))) { undeclared.add(name + "." + component.getName()); }
            }
            schema.path("properties").fields().forEachRemaining(property -> {
                if (property.getValue().has("$ref") && property.getValue().has("type")) { undeclared.add(name + "." + property.getKey() + " ($ref with a type)"); }
            });
        });
        return undeclared;
    }
    /** Whether Jackson writes the component when it is `null`: its own `@JsonInclude`, else its record's, else always. */
    static boolean sentAsNull(Class<?> type, java.lang.reflect.RecordComponent component) {
        var own = component.getAccessor().getAnnotation(JsonInclude.class);
        var include = own != null && own.value() != JsonInclude.Include.USE_DEFAULTS ? own : type.getAnnotation(JsonInclude.class);
        return include == null || include.value() == JsonInclude.Include.ALWAYS || include.value() == JsonInclude.Include.USE_DEFAULTS;
    }
    static boolean allowsNull(JsonNode property) {
        if (texts(property.path("type")).contains("null")) { return true; }
        for (var option : property.path("anyOf")) { if (texts(option.path("type")).contains("null")) { return true; } }
        return false;
    }
    /** The records a response reaches through its components, list elements and map values, with their schema names. */
    static void reach(java.lang.reflect.Type type, Map<Class<?>, String> schemas) {
        if (type instanceof ParameterizedType parameterized) {
            for (var argument : parameterized.getActualTypeArguments()) { reach(argument, schemas); }
        } else if (type instanceof Class<?> raw && raw.isRecord() && !schemas.containsKey(raw)) {
            var annotation = raw.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
            schemas.put(raw, annotation != null && !annotation.name().isBlank() ? annotation.name() : raw.getSimpleName());
            for (var component : raw.getRecordComponents()) { reach(component.getGenericType(), schemas); }
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
