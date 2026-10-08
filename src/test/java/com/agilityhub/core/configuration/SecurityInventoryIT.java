package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
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

    private static JsonNode effective(JsonNode document, JsonNode operation) {
        return operation.has("security") ? operation.path("security") : document.path("security");
    }
}
