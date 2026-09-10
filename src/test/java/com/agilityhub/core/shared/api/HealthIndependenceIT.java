package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.AccountAccess;
import com.agilityhub.core.shared.application.LocaleSettingsProvider;
import com.agilityhub.core.shared.application.TenantHostResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shared.scheduling.enabled=false", "management.server.port=0", "core.oidc.master-key="})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class HealthIndependenceIT {
    // Separate storage proves startup and every probe work before any club/account is seeded.
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("health_empty"));
    }
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @LocalManagementPort int managementPort;
    @MockitoSpyBean AccountAccess accounts;
    @MockitoSpyBean LocaleSettingsProvider locales;
    @MockitoSpyBean TenantHostResolver hosts;

    @Test void T_02_01_INC01_healthNeedsNoTenantLocaleAccountOrSeedData() throws Exception {
        assertEmpty();
        clearInvocations(accounts, locales, hosts);
        for (String host : new String[]{null, "app.agilitycanic.cat", "unknown.example.test"}) {
            var request = get("/api/v1/health").header("Accept-Language", "invalid;q=broken")
                    .header("Idempotency-Key", "ignored-for-get");
            if (host != null) { request.header("Host", host); }
            mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.version").isNotEmpty()).andExpect(jsonPath("$.builtAt").isNotEmpty());
        }
        for (String role : new String[]{"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"}) {
            mvc.perform(get("/api/v1/health").header("Host", "unknown.example.test")
                            .with(jwt().jwt(j -> j.subject("missing-account").claim("clubId", "missing-club").claim("locale", "es"))
                                    .authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        }
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + managementPort + "/actuator/health"))
                .header("Accept-Language", "es").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        verifyNoInteractions(accounts, locales, hosts);
        mvc.perform(get("/api/v1/branding").header("Host", "unknown.example.test"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verify(hosts).resolve("unknown.example.test");
        assertEmpty();
    }

    private void assertEmpty() {
        for (String collection : mongo.getCollectionNames()) {
            assertThat(mongo.getCollection(collection).countDocuments()).as(collection).isZero();
        }
    }
}
