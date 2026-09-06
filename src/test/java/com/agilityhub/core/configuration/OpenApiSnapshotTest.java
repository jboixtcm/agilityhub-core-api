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
        assertThat(strings(error.path("required"))).containsExactlyInAnyOrder("code", "message", "details", "traceId");
        assertThat(error.at("/properties/details/additionalProperties").asBoolean()).isTrue();
        document.path("paths").forEach(path -> path.forEach(operation -> {
            assertThat(strings(operation.path("tags"))).hasSize(1).noneMatch(tag -> tag.endsWith("-controller"));
            assertThat(names(operation.path("responses"))).contains("200", "400", "401", "403", "404", "409", "422", "429", "500", "4XX", "5XX");
            assertThat(operation.at("/responses/200/content")).isNotEmpty();
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
        assertThat(token.at("/requestBody/content/application~1x-www-form-urlencoded/schema/oneOf")).hasSize(2);
        assertThat(strings(token.at("/responses/200/content/application~1json/schema/required")))
                .containsExactlyInAnyOrder("access_token", "token_type", "expires_in", "refresh_token");
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"})
    void E0_T12_documentationIsGlobalForEveryRoleWithoutATenantClaim(String role) throws Exception {
        mvc.perform(get("/api/v1/openapi.json").header("Host", "another-club.example.test")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
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
