package com.agilityhub.core.configuration;

import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "shared.scheduling.enabled=false"})
@AutoConfigureMockMvc
class OpenApiSnapshotTest extends AbstractIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test void E0_T12_downloadAndWriteStableOpenApi31Contract() throws Exception {
        var client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/openapi.json"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var document = mapper.readTree(response.body());
        assertThat(document.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(document.at("/info/title").asText()).isEqualTo("AgilityHub Core API");
        assertThat(document.path("servers").findValuesAsText("url"))
                .containsExactly("https://core.agilitydoghub.com", "http://localhost:8080");
        assertThat(document.at("/components/securitySchemes/bearer/type").asText()).isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearer/scheme").asText()).isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearer/bearerFormat").asText()).isEqualTo("JWT");
        assertThat(document.at("/security/0/bearer").isArray()).isTrue();
        assertThat(names(document.path("paths"))).contains("/api/v1/branding", "/api/v1/me", "/api/v1/health",
                "/oauth2/token", "/api/v1/manifest.webmanifest", "/oauth2/jwks", "/.well-known/jwks.json")
                .noneMatch(path -> path.startsWith("/actuator") || path.contains("openapi") || path.contains("api-docs"));
        var error = document.at("/components/schemas/ApiError");
        assertThat(names(error.path("properties"))).containsExactlyInAnyOrder("code", "message", "details", "traceId");
        assertThat(strings(error.path("required"))).containsExactlyInAnyOrder("code", "message", "traceId");
        assertThat(error.at("/properties/details/additionalProperties").asBoolean()).isTrue();
        document.path("paths").forEach(path -> path.forEach(operation -> {
            assertThat(strings(operation.path("tags"))).hasSize(1).noneMatch(tag -> tag.endsWith("-controller"));
            assertThat(names(operation.path("responses"))).contains("400", "401", "403", "404", "409", "422", "429", "500", "4XX", "5XX");
            assertThat(names(operation.path("responses"))).anyMatch(code -> code.matches("[23][0-9]{2}"));
            operation.path("responses").fields().forEachRemaining(entry -> {
                if (entry.getKey().matches("[45](?:[0-9]{2}|XX)")) {
                    assertThat(names(entry.getValue().path("content"))).containsExactly("application/json");
                    assertThat(entry.getValue().at("/content/application~1json/schema/$ref").asText())
                            .isEqualTo("#/components/schemas/ApiError");
                }
            });
        }));
        for (String path : List.of("/api/v1/health", "/api/v1/branding", "/api/v1/manifest.webmanifest", "/oauth2/jwks", "/.well-known/jwks.json")) {
            assertThat(document.path("paths").path(path).path("get").path("security")).isEmpty();
            assertThat(document.path("paths").path(path).path("get").has("security")).isTrue();
        }
        for (String path : List.of("/api/v1/branding", "/api/v1/manifest.webmanifest")) {
            assertThat(document.path("paths").path(path).path("get").at("/responses/304/content")).isEmpty();
        }
        var me = document.path("paths").path("/api/v1/me").path("get");
        assertThat(me.has("security") ? me.path("security") : document.path("security")).isNotEmpty();
        var token = document.path("paths").path("/oauth2/token").path("post");
        assertThat(token.path("security")).isEmpty();
        assertThat(token.at("/requestBody/content/application~1x-www-form-urlencoded/schema/$ref").asText())
                .isEqualTo("#/components/schemas/TokenRequest");
        assertThat(strings(document.at("/components/schemas/TokenResponse/required")))
                .containsExactlyInAnyOrder("access_token", "token_type", "expires_in", "scope");
        assertIdentityContract(document);
        assertReferencesResolve(document, document);
        var second = mvc.perform(get("/api/v1/openapi.json").header("Host", "unregistered.example.test")
                        .header("X-Forwarded-Host", "untrusted.example.test").header("Accept-Language", "es"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String snapshot = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(document)) + "\n";
        assertThat(sorted(mapper.readTree(second.getContentAsString()))).isEqualTo(sorted(document));
        // The API has a fixed contract version and servers, so no build times or random ports need stripping.
        assertThat(snapshot).doesNotContain("localhost:" + port, "Generated server url", "x-generated-at");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), snapshot);
        if (!Boolean.getBoolean("openapi.snapshot.update")) {
            assertThat(snapshot).as("Regenerate the committed contract with bin/openapi-snapshot")
                    .isEqualTo(Files.readString(Path.of("docs/openapi/openapi.json")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"})
    void E0_T12_documentationIsGlobalForEveryRoleWithoutATenantClaim(String role) throws Exception {
        mvc.perform(get("/api/v1/openapi.json").header("Host", "another-club.example.test")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    private void assertIdentityContract(JsonNode document) {
        var expected = java.util.Map.ofEntries(
                java.util.Map.entry("/oauth2/token", List.of("post")),
                java.util.Map.entry("/.well-known/openid-configuration", List.of("get")),
                java.util.Map.entry("/.well-known/jwks.json", List.of("get")),
                java.util.Map.entry("/oauth2/authorize", List.of("get")),
                java.util.Map.entry("/oauth2/revoke", List.of("post")),
                java.util.Map.entry("/oauth2/userinfo", List.of("get")),
                java.util.Map.entry("/connect/logout", List.of("get")),
                java.util.Map.entry("/api/v1/auth/magic-link", List.of("post")),
                java.util.Map.entry("/api/v1/auth/handoff", List.of("post")),
                java.util.Map.entry("/api/v1/me", List.of("get", "patch")),
                java.util.Map.entry("/api/v1/me/password", List.of("put")),
                java.util.Map.entry("/api/v1/me/profile", List.of("put")),
                java.util.Map.entry("/api/v1/me/sessions", List.of("get")),
                java.util.Map.entry("/api/v1/me/sessions/{id}", List.of("delete")),
                java.util.Map.entry("/api/v1/me/onboarding", List.of("get", "put")),
                java.util.Map.entry("/api/v1/me/onboarding/postpone", List.of("post")),
                java.util.Map.entry("/api/v1/members/{id}/impersonation-token", List.of("post")),
                java.util.Map.entry("/api/v1/platform/accounts", List.of("post")),
                java.util.Map.entry("/api/v1/accounts/{id}/password", List.of("put")));
        var publicPaths = java.util.Set.of("/oauth2/token", "/.well-known/openid-configuration",
                "/.well-known/jwks.json", "/oauth2/authorize", "/connect/logout", "/api/v1/auth/magic-link");
        expected.forEach((path, methods) -> {
            assertThat(names(document.path("paths").path(path))).as(path).containsExactlyInAnyOrderElementsOf(
                    path.equals("/api/v1/me/profile") ? List.of("get", "put", "patch") : methods);
            methods.forEach(method -> {
                var operation = document.path("paths").path(path).path(method);
                assertThat(operation.path("summary").asText()).isNotBlank();
                assertThat(operation.path("description").asText()).isNotBlank();
                assertThat(strings(operation.path("tags"))).containsExactly("identity");
                var security = operation.has("security") ? operation.path("security") : document.path("security");
                assertThat(security.isEmpty()).as(path + " security").isEqualTo(publicPaths.contains(path));
                assertThat(operation.at("/responses/501/content/application~1json/schema/$ref").asText())
                        .isEqualTo("#/components/schemas/ApiError");
                assertThat(operation.path("parameters").findValuesAsText("name")).doesNotContain("clubId");
            });
        });
        assertThat(names(document.at("/components/schemas"))).contains("Me", "TokenResponse", "Profile", "Session",
                "OnboardingState", "OnboardingField", "RequiredConsent", "HandoffResponse", "ImpersonationTokenResponse", "PlatformAccountRequest");
        assertThat(document.path("paths").has("/auth/magic-link")).isFalse();
        assertThat(names(document.at("/components/schemas/Me/properties")))
                .containsExactlyInAnyOrder("account", "membership", "impersonation", "features");
        assertThat(document.at("/components/schemas/Me/properties/account/$ref").asText()).isEqualTo("#/components/schemas/MeAccount");
        assertThat(names(document.at("/components/schemas/MeAccount/properties")))
                .containsExactlyInAnyOrder("id", "email", "name", "locale", "platformRoles", "hasPassword", "emailVerifiedAt", "onboardingPending");
        assertThat(strings(document.at("/components/schemas/MeAccount/required")))
                .containsExactlyInAnyOrder("id", "email", "name", "locale", "platformRoles", "hasPassword", "onboardingPending");
        assertThat(document.at("/components/schemas/MeAccount/properties/hasPassword/type").asText()).isEqualTo("boolean");
        assertThat(document.at("/components/schemas/MeAccount/properties/onboardingPending/type").asText()).isEqualTo("boolean");
        assertThat(document.at("/components/schemas/MeAccount/properties/emailVerifiedAt/format").asText()).isEqualTo("date-time");
        assertThat(names(document.at("/components/schemas/AccountSummary/properties")))
                .containsExactlyInAnyOrder("id", "email", "name", "locale", "platformRoles");
        assertThat(names(document.at("/components/schemas/MembershipSummary/properties")))
                .containsExactlyInAnyOrder("clubId", "roles", "activeProfile", "profiles", "memberId", "instructorId", "defaultProfile", "rememberProfile", "gender");
        assertThat(strings(document.at("/components/schemas/MembershipSummary/required"))).doesNotContain("gender");
        assertThat(strings(document.at("/components/schemas/MembershipSummary/properties/gender/enum")))
                .containsExactly("MALE", "FEMALE", "OTHER");
        assertThat(strings(document.at("/components/schemas/Profile/enum"))).containsExactly("MEMBER", "INSTRUCTOR", "ADMIN");
        var requestFields = java.util.Map.ofEntries(
                java.util.Map.entry("MagicLinkRequest", List.of("email", "purpose", "client_id", "redirect_uri")),
                java.util.Map.entry("PasswordRequest", List.of("current", "new", "repeat")),
                java.util.Map.entry("ProfileRequest", List.of("activeProfile", "remember")),
                java.util.Map.entry("AccountPatchRequest", List.of("locale", "name")),
                java.util.Map.entry("RevokeRequest", List.of("token")),
                java.util.Map.entry("HandoffRequest", List.of("targetClientId")),
                java.util.Map.entry("ImpersonationRequest", List.of("reason")),
                java.util.Map.entry("PlatformAccountRequest", List.of("email", "name", "locale", "passwordHash")),
                java.util.Map.entry("AccountPasswordRequest", List.of("passwordHash")),
                java.util.Map.entry("OnboardingRequest", List.of("consentAccepted", "consentVersion", "fields", "imageConsent")),
                java.util.Map.entry("OnboardingFields", List.of("name", "locale", "phone")));
        requestFields.forEach((schema, fields) -> assertThat(names(document.path("components").path("schemas").path(schema).path("properties")))
                .as(schema).containsExactlyInAnyOrderElementsOf(fields));
        assertThat(strings(document.at("/components/schemas/OnboardingRequest/required")))
                .containsExactlyInAnyOrder("consentAccepted", "consentVersion");
        assertThat(document.at("/components/schemas/OnboardingRequest/properties/fields/$ref").asText())
                .isEqualTo("#/components/schemas/OnboardingFields");
        assertThat(document.at("/components/schemas/OnboardingFields/required")).isEmpty();
        assertThat(names(document.at("/components/schemas/OnboardingState/properties")))
                .containsExactlyInAnyOrder("pending", "postponeRemaining", "requiredConsent", "fields");
        assertThat(strings(document.at("/components/schemas/OnboardingState/required")))
                .containsExactlyInAnyOrder("pending", "postponeRemaining", "fields");
        assertThat(document.at("/components/schemas/OnboardingState/properties/pending/type").asText()).isEqualTo("boolean");
        assertThat(document.at("/components/schemas/OnboardingState/properties/postponeRemaining/type").asText()).isEqualTo("integer");
        assertThat(document.at("/components/schemas/OnboardingState/properties/fields/items/$ref").asText())
                .isEqualTo("#/components/schemas/OnboardingField");
        assertThat(document.at("/components/schemas/OnboardingState/properties/requiredConsent/$ref").asText())
                .isEqualTo("#/components/schemas/RequiredConsent");
        assertThat(document.at("/components/schemas/OnboardingState/properties/requiredConsent").has("type")).isFalse();
        assertThat(strings(document.at("/components/schemas/RequiredConsent/type"))).containsExactlyInAnyOrder("object", "null");
        assertThat(names(document.at("/components/schemas/OnboardingField/properties")))
                .containsExactlyInAnyOrder("key", "value", "required");
        assertThat(strings(document.at("/components/schemas/OnboardingField/required")))
                .containsExactlyInAnyOrder("key", "required");
        assertThat(strings(document.at("/components/schemas/OnboardingField/properties/value/type")))
                .containsExactlyInAnyOrder("string", "null");
        assertThat(names(document.at("/components/schemas/RequiredConsent/properties")))
                .containsExactlyInAnyOrder("policy", "version", "url");
        assertThat(strings(document.at("/components/schemas/RequiredConsent/required")))
                .containsExactlyInAnyOrder("policy", "version", "url");
        assertThat(strings(document.at("/components/schemas/RequiredConsent/properties/policy/enum")))
                .containsExactly("PLATFORM", "CLUB");
        assertThat(document.at("/components/schemas/RequiredConsent/properties/url/format").asText()).isEqualTo("uri");
        for (var route : java.util.Map.of("/api/v1/me/onboarding", List.of("get", "put"),
                "/api/v1/me/onboarding/postpone", List.of("post")).entrySet()) {
            for (String method : route.getValue()) {
                assertThat(document.path("paths").path(route.getKey()).path(method)
                        .at("/responses/200/content/application~1json/schema/$ref").asText())
                        .isEqualTo("#/components/schemas/OnboardingState");
            }
        }
        var onboarding = document.path("paths").path("/api/v1/me/onboarding").path("put");
        assertThat(onboarding.at("/responses/400/description").asText()).contains("VALIDATION_ERROR", "LOCALE_NOT_SUPPORTED");
        assertThat(onboarding.at("/responses/422/description").asText()).contains("CONSENT_VERSION_OUTDATED");
        assertThat(document.path("paths").path("/api/v1/me/onboarding/postpone").path("post").has("requestBody")).isFalse();
        var token = document.path("paths").path("/oauth2/token").path("post");
        assertThat(names(token.path("x-grants"))).containsExactlyInAnyOrder("password", "urn:agilityhub:grant:magic-link",
                "urn:agilityhub:grant:handoff", "authorization_code", "refresh_token");
        assertThat(strings(token.at("/x-grants/urn:agilityhub:grant:handoff/required"))).contains("client_id", "token");
        assertThat(token.at("/responses/200/headers/Set-Cookie/description").asText()).contains("HttpOnly", "SameSite=Strict", "Path=/oauth2/token");
        assertThat(strings(token.at("/x-grants/refresh_token/required"))).containsExactly("client_id");
        assertThat(strings(document.at("/components/schemas/TokenResponse/required"))).doesNotContain("refresh_token");
        assertThat(document.at("/components/schemas/RevokeRequest/required")).isEmpty();
        var platformRoles = document.path("paths").path("/api/v1/platform/accounts/{id}/platform-roles");
        for (String method : List.of("get", "put")) {
            assertThat(platformRoles.path(method).at("/responses/200/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/PlatformRoles");
            assertThat(platformRoles.path(method).path("description").asText()).contains("AGILITYHUB_ADMIN");
        }
        assertThat(platformRoles.path("put").at("/responses/409/description").asText()).contains("LAST_PLATFORM_ADMIN");
        assertThat(strings(document.at("/components/schemas/PlatformRoles/required"))).containsExactly("platformRoles");
        assertThat(token.at("/responses/429/headers/Retry-After")).isNotEmpty();
        assertThat(document.path("paths").path("/api/v1/auth/magic-link").at("/post/responses/202/content")).isEmpty();
        assertThat(document.path("paths").path("/api/v1/auth/magic-link").at("/post/responses/429/headers/Retry-After")).isNotEmpty();
        assertThat(document.path("paths").path("/connect/logout").at("/get/responses/302/content")).isEmpty();
        assertThat(document.path("paths").path("/api/v1/me/sessions").at("/get/responses/200/content/application~1json/schema/type").asText()).isEqualTo("array");
    }

    @Test void T_01_25_identityContractMatchesS01BodiesRolesAndResponseSchemas() throws Exception {
        var response = mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse();
        assertIdentityContract(mapper.readTree(response.getContentAsString()));
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode();
            names(node).stream().sorted().forEach(name -> result.set(name, sorted(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(value -> result.add(sorted(value)));
            return result;
        }
        return node;
    }

    private void assertReferencesResolve(JsonNode node, JsonNode document) {
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            assertThat(ref).startsWith("#/");
            assertThat(document.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
        }
        node.forEach(child -> assertReferencesResolve(child, document));
    }

    private List<String> names(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<String> strings(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }
}
