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
        properties = {"shared.scheduling.enabled=false", "management.server.port=0"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class HealthIndependenceIT {
    // Separate storage proves startup and every probe work before any club/account is seeded.
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    /**
     * E5-T27 round 3: a master key persists the signing key ring, as on staging and prod, so decoding any bearer reads it from
     * Mongo (`SigningKeys.publicKeys()`). The ring is written at startup: it is bootstrap, not club or account data.
     */
    static final String OIDC_MASTER = java.util.Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    static final String BOOTSTRAP_COLLECTION = "signing_keys";
    /** Club web origins: resolving one for CORS would query the clubs collection. The outage tests use their own, never cached. */
    static final String CLUB_ORIGIN = "https://app.health-club.example.test";
    static final String OUTAGE_CLUB_ORIGIN = "https://app.outage-club.example.test";
    static final String BEARER_OUTAGE_CLUB_ORIGIN = "https://app.bearer-outage-club.example.test";
    static final String ENCODED_OUTAGE_CLUB_ORIGIN = "https://app.encoded-outage-club.example.test";
    /** One of the configured platform hosts (`core.security.cors.platform-hosts`), allowed without the database. */
    static final String PLATFORM_ORIGIN = "https://id.agilitydoghub.com";
    /** The route as MVC also routes it: `%68` is an `h`. */
    static final URI ENCODED_HEALTH = URI.create("/api/v1/%68ealth");
    static final String PROBE_ACCOUNT = "health-probe-account";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("core.oidc.master-key", () -> OIDC_MASTER);
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("health_empty"));
    }
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired java.time.Clock clock;
    @org.springframework.beans.factory.annotation.Value("${identity.issuer}") String issuer;
    @LocalManagementPort int managementPort;
    @MockitoSpyBean AccountAccess accounts;
    @MockitoSpyBean LocaleSettingsProvider locales;
    @MockitoSpyBean TenantHostResolver hosts;
    @MockitoSpyBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

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

    /**
     * E5-T27 round 3 (step 5; review of 30-09): the health never reads a bearer, whatever the headers. Decoding one would read the
     * persisted key ring from Mongo, and an impersonation bearer would also look its grant up. With Mongo paused, bearers the api
     * signed itself get 503 DOWN within the bound, on the route and on its encoded spelling with an uncached club Origin.
     */
    @Test @org.junit.jupiter.api.Order(3)
    void E5_T27_aRealBearerGets503DownWithinItsBoundWhileTheDatabaseDoesNotAnswer() throws Exception {
        assertThat(mongo.getCollection(BOOTSTRAP_COLLECTION).countDocuments()).as("the signing key ring is persisted").isEqualTo(1);
        String member = bearer(false), impersonation = bearer(true);
        // Real bearers: the api's own decoder accepts them while the database answers.
        assertThat(decoder.decode(member).getSubject()).isEqualTo(PROBE_ACCOUNT);
        assertThat(decoder.decode(impersonation).getClaimAsBoolean("imp")).isTrue();
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        clearInvocations(accounts, locales, hosts, decoder);
        var requests = new java.util.LinkedHashMap<String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>();
        requests.put("member bearer", get("/api/v1/health").header("Authorization", "Bearer " + member));
        requests.put("impersonation bearer", get("/api/v1/health").header("Authorization", "Bearer " + impersonation));
        requests.put("encoded route, member bearer and club Origin", get(ENCODED_HEALTH)
                .header("Authorization", "Bearer " + member).header("Origin", BEARER_OUTAGE_CLUB_ORIGIN));
        // The route's CORS also matches the decoded path: without a bearer, an encoded route with a club Origin stays off the database.
        requests.put("encoded route and club Origin", get(ENCODED_HEALTH).header("Origin", ENCODED_OUTAGE_CLUB_ORIGIN));
        var outcomes = new org.assertj.core.api.SoftAssertions();
        var docker = MONGO.getDockerClient();
        docker.pauseContainerCmd(MONGO.getContainerId()).exec();
        try {
            for (var request : requests.entrySet()) {
                long started = System.nanoTime();
                org.springframework.mock.web.MockHttpServletResponse answer;
                try { answer = withinFiveSeconds(request.getValue()).getResponse(); }
                catch (java.util.concurrent.TimeoutException blocked) {
                    outcomes.fail("%s: no answer within 5 s, blocked on the paused database", request.getKey());
                    continue;
                }
                long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                outcomes.assertThat(answer.getStatus()).as(request.getKey()).isEqualTo(503);
                outcomes.assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(answer.getContentAsString()).path("status").asText())
                        .as(request.getKey()).isEqualTo("DOWN");
                outcomes.assertThat(elapsed).as("%s: the ping is bounded to 1 s", request.getKey()).isLessThan(3000);
                outcomes.assertThat(answer.getHeader("Access-Control-Allow-Origin")).as(request.getKey()).isNull();
            }
        } finally {
            docker.unpauseContainerCmd(MONGO.getContainerId()).exec();
        }
        outcomes.assertAll();
        awaitUp();
        verifyNoInteractions(decoder, accounts, locales, hosts);
    }

    /**
     * E5-T27 round 3 (step 5): the bearer resolver ignores the health's `Authorization` header, as it ignores the signed-file
     * routes', so the decoder is never called: a real bearer, an impersonation one and a malformed one all get UP, on the route
     * and on its encoded spelling. The neighbouring public route still reads the header and refuses the malformed one.
     */
    @Test @org.junit.jupiter.api.Order(4)
    void E5_T27_theHealthNeverReadsABearerAndAnotherRouteStillDoes() throws Exception {
        var tokens = new java.util.LinkedHashMap<String, String>();
        tokens.put("member bearer", bearer(false));
        tokens.put("impersonation bearer", bearer(true));
        tokens.put("malformed bearer", "not-a-jwt");
        clearInvocations(accounts, locales, hosts, decoder);
        var answers = new org.assertj.core.api.SoftAssertions();
        for (var token : tokens.entrySet()) {
            for (URI route : new URI[]{URI.create("/api/v1/health"), ENCODED_HEALTH}) {
                var answer = mvc.perform(get(route).header("Authorization", "Bearer " + token.getValue())).andReturn().getResponse();
                answers.assertThat(answer.getStatus() + " " + answer.getContentAsString()).as("%s on %s", token.getKey(), route)
                        .startsWith("200 ").contains("\"status\":\"UP\"");
            }
        }
        answers.assertAll();
        verifyNoInteractions(decoder, accounts, locales, hosts);
        mvc.perform(get("/api/v1/branding").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verify(decoder).decode("not-a-jwt");
    }

    /** An access token signed with the api's persisted ring, with the claims `TokenService` issues (a member, or an impersonation). */
    private String bearer(boolean impersonation) {
        var now = clock.instant();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer(issuer).subject(PROBE_ACCOUNT)
                .audience(java.util.List.of("clubs-app")).issuedAt(now).expiresAt(now.plus(java.time.Duration.ofMinutes(15)))
                .claim("clubId", "health-probe-club").claim("roles", java.util.List.of("MEMBER"));
        if (impersonation) {
            claims.claim("imp", true).claim("actorAccountId", "health-probe-admin").claim("memberId", "health-probe-member");
        }
        return encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    private void awaitUp() throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        int status = 0;
        while (System.nanoTime() < deadline && (status = mvc.perform(get("/api/v1/health")).andReturn().getResponse().getStatus()) != 200) {
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250));
        }
        assertThat(status).as("UP again once the database answers").isEqualTo(200);
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
            if (collection.equals(BOOTSTRAP_COLLECTION)) { continue; }
            assertThat(mongo.getCollection(collection).countDocuments()).as(collection).isZero();
        }
    }
}
