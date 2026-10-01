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
import java.util.HashSet;
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

/** INC-45: published authentication versus the real security chains; E8 joins at E11-T06 (E78). */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SecurityInventoryIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;

    @Test void E11_T03_INC45_everyPublishedOperationEnforcesItsBearerRequirement() throws Exception {
        clubs.save(PlatformFixtures.club("security-inventory", "inventory.example.test"));
        configs.invalidate("security-inventory"); hosts.invalidate();
        var published = mapper.readTree(Files.readString(Path.of("docs/openapi/openapi.json")));
        var live = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var deferred = new HashSet<String>();
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/e8-routes.json")) {
            for (var row : mapper.readTree(input)) { deferred.add(row.path("method").asText() + " " + row.path("path").asText()); }
        }
        var failures = new org.assertj.core.api.SoftAssertions();
        int checked = 0;
        for (var path : published.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (!Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(method.getKey())) { continue; }
                String label = method.getKey().toUpperCase() + " " + path.getKey();
                if (deferred.contains(label)) { continue; }
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
                var response = mvc.perform(request).andReturn().getResponse();
                if (!path.getKey().equals("/oauth2/token")) {
                    failures.assertThat(response.getStatus() == 401).as(label + " without bearer -> " + response.getStatus())
                            .isEqualTo(requiresBearer);
                } else {
                    failures.assertThat(response.getStatus()).as(label + " requires client authentication").isEqualTo(401);
                }
                checked++;
            }
        }
        assertThat(checked).as("all pre-E8 operations, including the public families").isGreaterThan(290);
        System.out.println("E11-T03 security inventory: " + checked + " operations; E8 deferred to E11-T06");
        failures.assertAll();
    }

    private static JsonNode effective(JsonNode document, JsonNode operation) {
        return operation.has("security") ? operation.path("security") : document.path("security");
    }
}
