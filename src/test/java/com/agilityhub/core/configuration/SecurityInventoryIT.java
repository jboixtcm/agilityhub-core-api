package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.api.SignedFileRequests;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** INC-45: published authentication versus the real security chains; includes E8 (E11-T06). */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SecurityInventoryIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;

    @Test void E11_T06_INC45_everyPublishedOperationEnforcesItsBearerRequirement() throws Exception {
        clubs.save(PlatformFixtures.club("security-inventory", "inventory.example.test"));
        configs.invalidate("security-inventory"); hosts.invalidate();
        var published = mapper.readTree(Files.readString(Path.of("docs/openapi/openapi.json")));
        var live = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var failures = new org.assertj.core.api.SoftAssertions();
        int checked = 0;
        for (var path : published.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (!Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(method.getKey())) { continue; }
                String label = method.getKey().toUpperCase() + " " + path.getKey();
                JsonNode requirement = effective(published, method.getValue());
                failures.assertThat(effective(live, live.path("paths").path(path.getKey()).path(method.getKey())))
                        .as(label + " live security").isEqualTo(requirement);
                boolean requiresBearer = !requirement.isEmpty();
                for (var alternative : requirement) { requiresBearer &= alternative.has("bearer"); }
                var request = request(HttpMethod.valueOf(method.getKey().toUpperCase()), path.getKey().replaceAll("\\{[^}]+}", "inventory-missing"))
                        .header("Host", "inventory.example.test").contentType("application/json");
                if (path.getKey().equals("/oauth2/token")) {
                    request.contentType("application/x-www-form-urlencoded").param("grant_type", "password")
                            .param("username", "inventory@example.test").param("password", "fictional-invalid-password");
                }
                if (path.getKey().equals("/webhooks/stripe/{clubId}")) request.content("{}");
                var response = mvc.perform(request).andReturn().getResponse();
                if (path.getKey().equals("/webhooks/stripe/{clubId}")) {
                    failures.assertThat(response.getStatus()).as(label + " requires Stripe signature").isEqualTo(401);
                    failures.assertThat(mapper.readTree(response.getContentAsString()).path("code").asText())
                            .isEqualTo("WEBHOOK_SIGNATURE_INVALID");
                } else if (path.getKey().equals("/api/v1/checkout-sessions/{id}")) {
                    failures.assertThat(response.getStatus()).as(label + " requires signup capability").isEqualTo(401);
                    failures.assertThat(mapper.readTree(response.getContentAsString()).path("code").asText())
                            .isEqualTo("UNAUTHENTICATED");
                } else if (!path.getKey().equals("/oauth2/token")) {
                    failures.assertThat(response.getStatus() == 401).as(label + " without bearer -> " + response.getStatus())
                            .isEqualTo(requiresBearer);
                } else {
                    failures.assertThat(response.getStatus()).as(label + " requires client authentication").isEqualTo(401);
                }
                checked++;
            }
        }
        int publishedCount = 0;
        for (var path : published.path("paths")) {
            for (String method : Set.of("get", "post", "put", "patch", "delete", "head", "options")) {
                if (path.has(method)) publishedCount++;
            }
        }
        assertThat(checked).as("every published operation including S12/S13").isEqualTo(publishedCount);
        System.out.println("E11-T06 security inventory: " + checked + " / " + publishedCount + " operations");
        failures.assertAll();
    }

    /**
     * INC-45 (E11-T06): the routes a URL signature authorises ({@link SignedFileRequests}) are hidden from the published contract,
     * so the walk above never reaches them. Without `expires` and `signature` each one is refused before its service runs: the
     * framework's missing-parameter 400 keeps its status and takes the catalog's `VALIDATION_ERROR` (ApiExceptionHandler,
     * CATALEG_ERRORS §1). Never a file and never a 401: no bearer is read there, so an invalid or an authenticated one changes nothing.
     */
    @Test void E11_T06_INC45_hiddenSignedRoutesRefuseAnUnsignedRequest() throws Exception {
        clubs.save(PlatformFixtures.club("security-inventory", "inventory.example.test"));
        configs.invalidate("security-inventory"); hosts.invalidate();
        var published = mapper.readTree(Files.readString(Path.of("docs/openapi/openapi.json"))).path("paths");
        // One concrete path per SignedFileRequests pattern: UPLOAD, both DOWNLOAD alternatives and the two signup paths.
        String[][] routes = {
                {"PUT", "/api/v1/attachments/uploads/{id}", "/api/v1/attachments/uploads/inventory-upload"},
                {"GET", "/api/v1/attachments/files/{id}", "/api/v1/attachments/files/inventory-file"},
                {"GET", "/api/v1/remittances/files/{clubId}/{id}", "/api/v1/remittances/files/security-inventory/inventory-remittance"},
                {"PUT", "/api/v1/signup/uploads", "/api/v1/signup/uploads"},
                {"GET", "/api/v1/signup/files", "/api/v1/signup/files"}};
        var failures = new org.assertj.core.api.SoftAssertions();
        for (String[] route : routes) {
            String label = route[0] + " " + route[2];
            failures.assertThat(SignedFileRequests.matches(new MockHttpServletRequest(route[0], route[2]))).as(label + " is signed").isTrue();
            failures.assertThat(published.has(route[1])).as(label + " is hidden").isFalse();
            for (String bearer : List.of("no bearer", "an invalid bearer", "the club ADMIN's bearer")) {
                var request = request(HttpMethod.valueOf(route[0]), route[2]).header("Host", "inventory.example.test").contentType("application/json");
                // The signup routes name their file in `fileKey`; only the signature and its expiry are missing.
                if (route[2].startsWith("/api/v1/signup/")) request.param("fileKey", "inventory-file-key");
                if (bearer.equals("an invalid bearer")) request.header("Authorization", "Bearer not-a-token");
                if (bearer.equals("the club ADMIN's bearer")) {
                    request.with(jwt().jwt(j -> j.subject("inventory-admin").claim("clubId", "security-inventory"))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
                }
                var response = mvc.perform(request).andReturn().getResponse();
                String body = response.getContentAsString();
                failures.assertThat(response.getStatus()).as(label + " unsigned with " + bearer + ": " + body).isEqualTo(400);
                failures.assertThat(mapper.readTree(body.isEmpty() ? "{}" : body).path("code").asText())
                        .as(label + " unsigned with " + bearer).isEqualTo("VALIDATION_ERROR");
            }
        }
        failures.assertAll();
    }

    private static JsonNode effective(JsonNode document, JsonNode operation) {
        return operation.has("security") ? operation.path("security") : document.path("security");
    }
}
