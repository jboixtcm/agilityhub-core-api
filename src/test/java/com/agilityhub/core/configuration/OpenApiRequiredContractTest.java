package com.agilityhub.core.configuration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

class OpenApiRequiredContractTest {
    private static final Set<String> ALL_OPTIONAL = Set.of("AccountPatchRequest", "ClubAddress", "ClubPwa",
            "ConsentPatch", "ErasureInput", "ImpersonationRequest", "OnboardingFields",
            "PlanTexts", "PlanTextsInput", "PublicPlanTexts", "ReasonRequest", "RevokeRequest", "SepaInput");

    @Test void E1_T11_everySnapshotObjectDeclaresItsRequiredProperties() throws Exception {
        var schemas = new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile()).at("/components/schemas");
        var allOptional = new java.util.TreeSet<String>();
        schemas.fields().forEachRemaining(entry -> {
            var schema = entry.getValue();
            if (!schema.has("properties") && !"object".equals(schema.path("type").asText())
                    && !strings(schema.path("type")).contains("object")) { return; }
            assertThat(schema.path("required").isArray()).as(entry.getKey()).isTrue();
            assertThat(names(schema.path("properties"))).containsAll(strings(schema.path("required")));
            if (schema.path("required").isEmpty()) { allOptional.add(entry.getKey()); }
        });
        assertThat(allOptional).containsExactlyInAnyOrderElementsOf(ALL_OPTIONAL);
        assertThat(required(schemas, "BrandingResponse")).contains("club", "theme", "locales", "defaultLocale");
        assertThat(required(schemas, "ClubSummary")).containsExactlyInAnyOrder("slug", "name");
        assertThat(required(schemas, "Colors")).hasSize(12)
                .containsExactlyInAnyOrderElementsOf(names(schemas.at("/Colors/properties")));
        assertThat(required(schemas, "Me")).containsExactlyInAnyOrder("account", "features");
        assertThat(required(schemas, "MeAccount")).containsExactlyInAnyOrder("id", "email", "name", "locale",
                "platformRoles", "hasPassword", "onboardingPending");
        assertThat(required(schemas, "ApiError")).containsExactlyInAnyOrder("code", "message", "traceId");
        assertThat(required(schemas, "MembershipSummary")).containsExactlyInAnyOrder("clubId", "roles", "profiles", "rememberProfile");
        assertThat(required(schemas, "Session")).containsExactlyInAnyOrder("id", "clientId", "deviceLabel", "createdAt", "expiresAt");
        assertThat(required(schemas, "Theme")).containsExactlyInAnyOrder("colors", "fontFamily", "radius", "ringPalette", "mode");
        assertThat(required(schemas, "TokenResponse")).containsExactlyInAnyOrder("access_token", "token_type", "expires_in", "scope");
        assertThat(required(schemas, "OnboardingState")).containsExactlyInAnyOrder("pending", "postponeRemaining", "fields");
        assertThat(required(schemas, "OnboardingField")).containsExactlyInAnyOrder("key", "required");
        assertThat(required(schemas, "TokenRequest")).containsExactlyInAnyOrder("grant_type", "client_id");
        assertThat(required(schemas, "MemberPatch")).containsExactly("version");
        assertThat(required(schemas, "Manifest")).containsExactlyInAnyOrderElementsOf(names(schemas.at("/Manifest/properties")));
    }

    @Test void E1_T11_javaDefaultsPreserveOptOutsRenamedPropertiesAndSharedReferences() {
        var converters = converters();
        var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(Defaults.class).resolveAsRef(true));
        io.swagger.v3.oas.models.media.Schema<?> model = resolved.referencedSchemas.get("Defaults");
        assertThat(model.getRequired()).containsExactlyInAnyOrder("plain", "items", "labels", "explicit", "wire_name",
                "schema_name", "child", "nullableItems");
        assertThat(model.getProperties()).doesNotContainKey("hidden");
        assertThat(resolved.referencedSchemas.get("Child").getRequired()).containsExactly("name");
        assertThat(model.getProperties().get("child").get$ref()).isEqualTo(model.getProperties().get("optionalChild").get$ref());
        var generic = converters.resolveAsResolvedSchema(new AnnotatedType(
                new com.fasterxml.jackson.core.type.TypeReference<Envelope<Child>>() { }.getType()).resolveAsRef(true));
        assertThat(generic.referencedSchemas.get("EnvelopeChild").getRequired()).containsExactlyInAnyOrder("value", "items");
    }

    @Test void E1_T11_emptyRequiredArraysSurviveOpenApi31Serialization() throws Exception {
        var result = converters().resolveAsResolvedSchema(new AnnotatedType(AllOptional.class));
        var mapper = Json31.mapper().copy().registerModule(OpenApiConfiguration.requiredArraysModule());
        var serialized = mapper.readTree(mapper.writeValueAsString(result.schema));
        assertThat(serialized.path("required").isArray()).as(serialized.toString()).isTrue();
        assertThat(serialized.path("required")).isEmpty();
        assertThat(serialized.path("properties").path("value").path("type").asText()).isEqualTo("string");
        assertThat(serialized.path("properties").path("value").has("required")).isFalse();
        assertThat(serialized.has("nullable")).isFalse();
    }

    private ModelConverters converters() {
        var converters = new ModelConverters(true);
        converters.addConverter(new RequiredPropertiesModelConverter(Json31.mapper()));
        return converters;
    }

    record Defaults(String plain, List<String> items, Map<String, String> labels,
            @Schema(requiredMode = REQUIRED) String explicit,
            @JsonProperty("wire_name") String renamed,
            @Schema(name = "schema_name") String schemaRenamed,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Schema(requiredMode = NOT_REQUIRED) String writeOnly,
            Optional<String> optional, @org.springframework.lang.Nullable String springNullable,
            @org.jspecify.annotations.Nullable String jspecifyNullable,
            List<@org.jspecify.annotations.Nullable String> nullableItems,
            @Schema(requiredMode = NOT_REQUIRED) String optOut,
            @Schema(nullable = true, requiredMode = REQUIRED) String nullable,
            Child child, @Schema(requiredMode = NOT_REQUIRED) Child optionalChild,
            @JsonIgnore String hidden) { }
    record Child(String name) { }
    record Envelope<T>(T value, List<T> items, Optional<T> optional) { }
    @Schema(requiredProperties = "value")
    record AllOptional(@Schema(requiredMode = NOT_REQUIRED) String value) { }

    private static List<String> required(JsonNode schemas, String name) { return strings(schemas.path(name).path("required")); }
    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }
    private static List<String> names(JsonNode node) {
        var values = new ArrayList<String>();
        node.fieldNames().forEachRemaining(values::add);
        return values;
    }
}
