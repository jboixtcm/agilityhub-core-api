package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Import({IdempotencyIT.Config.class, IdempotencyIT.TestController.class})
class IdempotencyIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired TestController controller;
    @Autowired IdempotencyRepository repository;

    @BeforeEach void prepare() {
        controller = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(controller);
        clock.setInstant(Instant.parse("2030-01-01T00:00:00Z"));
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), IdempotencyRecord.class);
        if (!mongo.collectionExists("idempotency_effects")) { mongo.createCollection("idempotency_effects"); }
        mongo.getCollection("idempotency_effects").deleteMany(new Document());
        controller.calls.set(0); controller.entered = null; controller.release = null;
    }

    private MockHttpServletRequestBuilder request(String key, String body, String club, String account) {
        return post("/api/v1/test/idempotency").with(csrf()).with(jwt().jwt(token -> token
                .subject(account).claim("clubId", club)).authorities(() -> "ROLE_MEMBER"))
                .header("Idempotency-Key", key).contentType("application/json").content(body);
    }

    @Test void E0_T04_replayReturnsIdenticalStatusBodyAndHeaders() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = mvc.perform(request(key, "original", "club-a", "account-a"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var replay = mvc.perform(request(key, "original", "club-a", "account-a"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(replay.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
        assertThat(replay.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        assertThat(replay.getContentType()).isEqualTo(first.getContentType());
        assertThat(controller.calls).hasValue(1);
        assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isEqualTo(1);
        assertThat(mongo.findAll(IdempotencyRecord.class)).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(IdempotencyRecord.Status.DONE);
            assertThat(record.clubId()).isEqualTo("club-a");
        });
        System.out.println("idempotency_records indexes: " + mongo.getCollection("idempotency_records")
                .listIndexes().into(new java.util.ArrayList<>()));
    }
    @Test void E0_T04_differentBodyOrTargetConflicts() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(request(key, "original", "club-a", "account-a")).andExpect(status().isCreated());
        mvc.perform(request(key, "changed", "club-a", "account-a"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.details.reason").value("DIFFERENT_REQUEST"));
        mvc.perform(request(key, "original", "club-a", "account-a").queryParam("mode", "different"))
                .andExpect(status().isConflict());
        assertThat(controller.calls).hasValue(1);
    }
    @Test void E0_T04_concurrentDuplicateConflictsWhileFirstIsInProgress() throws Exception {
        String key = UUID.randomUUID().toString();
        controller.entered = new CountDownLatch(1); controller.release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> mvc.perform(request(key, "original", "club-a", "account-a"))
                    .andExpect(status().isCreated()));
            try {
                assertThat(controller.entered.await(10, TimeUnit.SECONDS)).isTrue();
                mvc.perform(request(key, "original", "club-a", "account-a"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                        .andExpect(jsonPath("$.details.reason").value("IN_PROGRESS"));
            } finally { controller.release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(controller.calls).hasValue(1);
    }
    @Test void E0_T04_keysAreIsolatedByTenantAndAccount() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(request(key, "one", "club-a", "account-a")).andExpect(status().isCreated());
        mvc.perform(request(key, "two", "club-b", "account-a")).andExpect(status().isCreated());
        mvc.perform(request(key, "three", "club-a", "account-b")).andExpect(status().isCreated());
        assertThat(controller.calls).hasValue(3);
        mvc.perform(request(key, "three", "club-a", "account-b").with(jwt().jwt(token -> token
                .subject("account-b").claim("clubId", "club-a")).authorities(() -> "ROLE_INSTRUCTOR")))
                .andExpect(status().isForbidden());
        mvc.perform(request(key, "two", "club-a", "account-a").header("X-Club-Id", "club-b"))
                .andExpect(status().isConflict());
    }
    @Test void E0_T08_conflictsAreLocalizedAndReplayPreservesTheOriginalLanguage() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(request(key, "original", "club-a", "account-a").header("Accept-Language", "es"))
                .andExpect(status().isCreated()).andExpect(header().string("Content-Language", "es"));
        var replay = mvc.perform(request(key, "original", "club-a", "account-a").header("Accept-Language", "en"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(replay.getHeaders("Content-Language")).containsExactly("es");
        mvc.perform(request(key, "changed", "club-a", "account-a").header("Accept-Language", "es"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.details.reason").value("DIFFERENT_REQUEST"))
                .andExpect(jsonPath("$.message").value("Esta clave de petición ya se ha utilizado o la petición sigue en curso."));
        assertThat(controller.calls).hasValue(1);
    }
    @Test void E0_T04_expiredKeysAreReusedAndHeadersAreOptional() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(request(key, "original", "club-a", "account-a")).andExpect(status().isCreated());
        clock.advance(Duration.ofHours(24));
        mvc.perform(request(key, "changed", "club-a", "account-a")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/test/idempotency").with(csrf()).with(user("account-a").roles("MEMBER"))
                .content("without-key")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/health").header("Idempotency-Key", key)).andExpect(status().isOk());
        assertThat(controller.calls).hasValue(3);
    }
    @Test void E0_T04_invalidKeysAndUntrustedIdentityAreRejected() throws Exception {
        mvc.perform(request("invalid", "original", "club-a", "account-a"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(request("1-1-1-1-1", "original", "club-a", "account-a"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(UUID.randomUUID().toString(), "original", "", "account-a"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        mvc.perform(request(UUID.randomUUID().toString(), "original", "club-a", "account-a")
                .with(user("account-a").roles("MEMBER")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(controller.calls).hasValue(0);
    }
    @Test void E0_T04_failureRollsBackEffectsAndReleasesTheKey() throws Exception {
        String key = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(request(key, "throw", "club-a", "account-a"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
            assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isZero();
            assertThat(mongo.findAll(IdempotencyRecord.class)).isEmpty();
        }
        assertThat(controller.calls).hasValue(2);
        mvc.perform(request(key, "retry", "club-a", "account-a")).andExpect(status().isCreated());
        assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isEqualTo(1);
    }

    @Test void E3_T06_INC02_transactionalFailureReturns500AndReleasesTheKey() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/test/idempotency/transactional").with(csrf())
                        .with(jwt().jwt(token -> token.subject("account-a").claim("clubId", "club-a"))
                                .authorities(() -> "ROLE_MEMBER"))
                        .header("Idempotency-Key", key).contentType("application/json").content("throw"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isZero();
        assertThat(mongo.findAll(IdempotencyRecord.class)).isEmpty();
        mvc.perform(request(key, "retry", "club-a", "account-a")).andExpect(status().isCreated());
        assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false) static class Config {
        @Bean @Order(0) SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/test/**").authorizeHttpRequests(auth -> auth.anyRequest()
                    .hasRole("MEMBER")).build();
        }
    }
    @RestController static class TestController {
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        private final MongoTemplate mongo;
        TestController(MongoTemplate mongo) { this.mongo = mongo; }
        @org.springframework.transaction.annotation.Transactional
        @PostMapping("/api/v1/test/idempotency/transactional")
        public ResponseEntity<Map<String, Object>> transactional(@RequestBody String body) throws InterruptedException {
            return post(body);
        }
        @PostMapping("/api/v1/test/idempotency") ResponseEntity<Map<String, Object>> post(@RequestBody String body)
                throws InterruptedException {
            int count = calls.incrementAndGet();
            mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("value", body), "idempotency_effects");
            if (body.equals("throw")) { throw new IllegalStateException("Simulated failure"); }
            if (entered != null) { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test release timed out");
            } }
            return ResponseEntity.created(java.net.URI.create("/api/v1/test/idempotency/" + count))
                    .body(Map.of("body", body, "count", count));
        }
    }
}
