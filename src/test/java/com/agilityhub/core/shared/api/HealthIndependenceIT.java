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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shared.scheduling.enabled=false", "management.server.port=0", "core.oidc.master-key="})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class HealthIndependenceIT {
    // Separate storage proves startup and every probe work before any club/account is seeded.
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    /** Club web origins: resolving one for CORS would query the clubs collection. The outage test uses its own, never cached. */
    static final String CLUB_ORIGIN = "https://app.health-club.example.test";
    static final String OUTAGE_CLUB_ORIGIN = "https://app.outage-club.example.test";
    /** One of the configured platform hosts (`core.security.cors.platform-hosts`), allowed without the database. */
    static final String PLATFORM_ORIGIN = "https://id.agilitydoghub.com";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("health_empty"));
    }
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @LocalManagementPort int managementPort;
    @MockitoSpyBean AccountAccess accounts;
    @MockitoSpyBean LocaleSettingsProvider locales;
    @MockitoSpyBean TenantHostResolver hosts;

    @Test @org.junit.jupiter.api.Order(1) void T_02_01_INC01_healthNeedsNoTenantLocaleAccountOrSeedData() throws Exception {
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
        // E5-T27 round 2 (review #2, ruling E71): CORS on the health reads the configured platform hosts only, never a club's domains.
        mvc.perform(get("/api/v1/health").header("Origin", CLUB_ORIGIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/health").header("Origin", PLATFORM_ORIGIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(header().string("Access-Control-Allow-Origin", PLATFORM_ORIGIN));
        mvc.perform(post("/api/v1/health").header("Host", "unknown.example.test")
                        .header("Idempotency-Key", "ignored-for-health"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
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

    /**
     * E5-T27 step 5 (A7-07, INC-01 semantics): UP means the database answers. With the Mongo container paused, the bounded (1 s)
     * ping fails: 503 with status DOWN in the same envelope, within the bound, and a WARN with the request's traceId. Once the
     * database is back the health is 200 UP again. Still no tenant, locale or account is read.
     */
    @Test @org.junit.jupiter.api.Order(2)
    void E5_T27_healthAnswers503DownWithinItsBoundWhileTheDatabaseDoesNotAnswer(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        clearInvocations(accounts, locales, hosts);
        var docker = MONGO.getDockerClient();
        docker.pauseContainerCmd(MONGO.getContainerId()).exec();
        try {
            long started = System.nanoTime();
            var result = mvc.perform(get("/api/v1/health").header("Host", "unknown.example.test")).andReturn();
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            var down = result.getResponse();
            assertThat(down.getStatus()).isEqualTo(503);
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(down.getContentAsString());
            assertThat(body.path("status").asText()).isEqualTo("DOWN");
            assertThat(body.path("version").asText()).isNotEmpty(); assertThat(body.path("builtAt").asText()).isNotEmpty();
            assertThat(body.size()).isEqualTo(3);
            assertThat(elapsed).as("the ping is bounded to 1 s").isLessThan(3000);
            String traceId = RequestTraceFilter.traceId(result.getRequest());
            assertThat(output.getAll()).contains("Health DOWN").contains("traceId=" + traceId);
            // E5-T27 round 2 (review #2, ruling E71): an Origin never sends the health to the database through CORS. A club origin whose
            // host is not cached gets the DOWN envelope within the bound (no CORS headers); a platform origin also gets its CORS header.
            for (String origin : new String[]{OUTAGE_CLUB_ORIGIN, PLATFORM_ORIGIN}) {
                started = System.nanoTime();
                var answer = withinFiveSeconds(get("/api/v1/health").header("Origin", origin)).getResponse();
                elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(answer.getStatus()).as(origin).isEqualTo(503);
                assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(answer.getContentAsString()).path("status").asText())
                        .as(origin).isEqualTo("DOWN");
                assertThat(elapsed).as("%s: the ping is bounded to 1 s", origin).isLessThan(3000);
                assertThat(answer.getHeader("Access-Control-Allow-Origin")).as(origin).isEqualTo(origin.equals(PLATFORM_ORIGIN) ? origin : null);
            }
        } finally {
            docker.unpauseContainerCmd(MONGO.getContainerId()).exec();
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        int status = 0;
        while (System.nanoTime() < deadline && (status = mvc.perform(get("/api/v1/health")).andReturn().getResponse().getStatus()) != 200) {
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250));
        }
        assertThat(status).as("UP again once the database answers").isEqualTo(200);
        verifyNoInteractions(accounts, locales, hosts);
    }

    /** Runs the request on its own thread, so that a request stuck on the paused database fails the test instead of hanging it. */
    private org.springframework.test.web.servlet.MvcResult withinFiveSeconds(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try { return executor.submit(() -> mvc.perform(request).andReturn()).get(5, java.util.concurrent.TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
    }

    private void assertEmpty() {
        for (String collection : mongo.getCollectionNames()) {
            assertThat(mongo.getCollection(collection).countDocuments()).as(collection).isZero();
        }
    }
}
