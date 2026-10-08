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
    /** A spy (E5-T31): a test makes Mongo fail under the filter's failure path; every other call is the real one. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean IdempotencyRepository repository;

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

    /**
     * E5-T27 step 4 (INC-23, ruling E46; CONVENCIONS_API §7): the key applies to every route that declares it, whatever the method.
     * A keyed PUT replays its 200 and a keyed DELETE its 204 without running again; a GET is never filtered, and neither is a PUT
     * whose handler does not declare the header. The set comes from the handlers' own declarations (these test routes included).
     */
    @Test void E5_T27_keyedPutAndDeleteReplayTheStoredAnswerAndAGetOrAnUndeclaredPutIsNeverFiltered() throws Exception {
        var member = jwt().jwt(token -> token.subject("account-a").claim("clubId", "club-a")).authorities(() -> "ROLE_MEMBER");
        String key = UUID.randomUUID().toString();
        var first = mvc.perform(put("/api/v1/test/idempotency/item-1").with(csrf()).with(member).header("Idempotency-Key", key)
                .contentType("application/json").content("edited")).andExpect(status().isOk()).andReturn().getResponse();
        var replay = mvc.perform(put("/api/v1/test/idempotency/item-1").with(csrf()).with(member).header("Idempotency-Key", key)
                .contentType("application/json").content("edited")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(replay.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
        assertThat(controller.calls).hasValue(1);
        mvc.perform(put("/api/v1/test/idempotency/item-1").with(csrf()).with(member).header("Idempotency-Key", key)
                        .contentType("application/json").content("other"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("DIFFERENT_REQUEST"));
        String deletion = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            var deleted = mvc.perform(delete("/api/v1/test/idempotency/item-1").with(csrf()).with(member).header("Idempotency-Key", deletion))
                    .andExpect(status().isNoContent()).andReturn().getResponse();
            assertThat(deleted.getContentAsByteArray()).isEmpty();
        }
        assertThat(controller.calls).hasValue(2);
        assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isEqualTo(2);
        assertThat(mongo.findAll(IdempotencyRecord.class)).hasSize(2).allMatch(record -> record.status() == IdempotencyRecord.Status.DONE);
        String read = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(get("/api/v1/test/idempotency/item-1").with(member).header("Idempotency-Key", read)).andExpect(status().isOk());
            mvc.perform(put("/api/v1/test/idempotency/item-1/plain").with(csrf()).with(member).header("Idempotency-Key", read)
                    .contentType("application/json").content("plain")).andExpect(status().isOk());
        }
        assertThat(controller.calls).as("two GETs and two undeclared PUTs ran").hasValue(6);
        assertThat(mongo.findAll(IdempotencyRecord.class)).hasSize(2);
    }

    /**
     * E5-T30 round 2 (CONVENCIONS_API §7, ruling E79): the first request holds its claim and stops (its process died: nothing
     * cleans it up). Inside the claim's lease a retry still gets `409 {reason: IN_PROGRESS}`; once the lease has passed, the
     * first retry with the same key and body takes the claim over and runs, while another body is never let in. If the first
     * request wakes up after all, its claim is gone: its answer is not stored and its effects roll back.
     */
    @Test void E5_T30_aStoppedRequestsClaimIsTakenOverOnceItsLeasePassedAndItsLateAnswerChangesNothing() throws Exception {
        String key = UUID.randomUUID().toString();
        controller.entered = new CountDownLatch(1); controller.release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stopped = executor.submit(() -> mvc.perform(request(key, "original", "club-a", "account-a")).andReturn().getResponse());
            org.springframework.mock.web.MockHttpServletResponse retried;
            try {
                assertThat(controller.entered.await(10, TimeUnit.SECONDS)).isTrue();
                // Retries are no longer held by the controller; the stopped request still waits for its release.
                controller.entered = null;
                clock.advance(IdempotencyRepository.CLAIM_LEASE.minusSeconds(1));
                mvc.perform(request(key, "original", "club-a", "account-a"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                        .andExpect(jsonPath("$.details.reason").value("IN_PROGRESS"));
                clock.advance(Duration.ofSeconds(2));
                mvc.perform(request(key, "changed", "club-a", "account-a"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("DIFFERENT_REQUEST"));
                retried = mvc.perform(request(key, "original", "club-a", "account-a")).andExpect(status().isCreated()).andReturn().getResponse();
                assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isEqualTo(1);
            } finally { controller.release.countDown(); }
            var late = stopped.get(20, TimeUnit.SECONDS);
            assertThat(late.getStatus()).as(late.getContentAsString()).isEqualTo(409);
            assertThat(late.getContentAsString()).contains("\"IDEMPOTENCY_KEY_REUSED\"", "\"IN_PROGRESS\"");
            assertThat(controller.calls).hasValue(2);
            assertThat(mongo.getCollection("idempotency_effects").countDocuments()).as("the stopped request's effect rolled back").isEqualTo(1);
            assertThat(mongo.findAll(IdempotencyRecord.class)).singleElement().satisfies(record -> assertThat(record.status()).isEqualTo(IdempotencyRecord.Status.DONE));
            var replay = mvc.perform(request(key, "original", "club-a", "account-a")).andExpect(status().isCreated()).andReturn().getResponse();
            assertThat(replay.getContentAsByteArray()).isEqualTo(retried.getContentAsByteArray());
        }
        assertThat(controller.calls).hasValue(2);
    }

    /**
     * E5-T31 (review E5-T30 #5): the answer cannot be stored because Mongo failed, and checking the claim then fails too. The
     * original failure is the one that surfaces, with the second one as suppressed, and the claim is still released, so the
     * retry with the same key runs at once. When releasing the claim fails as well (Mongo unreachable), the original failure
     * still surfaces, with both; the claim then stays until its lease has passed, and the retry after it runs.
     * CONVENCIONS_API §7 has no test id, so this class keeps its task-based names (review E5-T30 #8).
     */
    @Test void E5_T31_aFailingClaimCheckOrReleaseNeverHidesTheOriginalFailure() throws Exception {
        String key = UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new com.mongodb.MongoException("answer store unreachable (injected)")).doCallRealMethod()
                .when(repository).complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doThrow(new com.mongodb.MongoException("claim check unreachable (injected)")).doCallRealMethod()
                .when(repository).held(org.mockito.ArgumentMatchers.any());
        assertThat(surfaced(key)).hasMessage("answer store unreachable (injected)").satisfies(failure ->
                assertThat(failure.getSuppressed()).extracting(Throwable::getMessage).containsExactly("claim check unreachable (injected)"));
        assertThat(mongo.findAll(IdempotencyRecord.class)).as("the claim was released").isEmpty();
        assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isZero();
        mvc.perform(request(key, "original", "club-a", "account-a")).andExpect(status().isCreated());
        assertThat(controller.calls).hasValue(2);

        String unreachable = UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new com.mongodb.MongoException("answer store unreachable (injected)")).doCallRealMethod()
                .when(repository).complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doThrow(new com.mongodb.MongoException("claim check unreachable (injected)")).doCallRealMethod()
                .when(repository).held(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doThrow(new com.mongodb.MongoException("claim release unreachable (injected)")).doCallRealMethod()
                .when(repository).abandon(org.mockito.ArgumentMatchers.any());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.agilityhub.core.shared.api.IdempotencyFilter.class);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logged.start(); logger.addAppender(logged);
        try {
            assertThat(surfaced(unreachable)).hasMessage("answer store unreachable (injected)").satisfies(failure ->
                    assertThat(failure.getSuppressed()).extracting(Throwable::getMessage)
                            .containsExactly("claim check unreachable (injected)", "claim release unreachable (injected)"));
        } finally { logger.detachAppender(logged); }
        // E5-T31's review nit (done in E8-T06): each failed clean-up step leaves one WARN with the record id and the club only.
        assertThat(logged.list).filteredOn(line -> line.getLevel() == ch.qos.logback.classic.Level.WARN)
                .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .satisfiesExactly(held -> assertThat(held).startsWith("Idempotency held check failed: recordId=").endsWith("clubId=club-a"),
                        released -> assertThat(released).startsWith("Idempotency abandon failed: recordId=").endsWith("clubId=club-a"))
                .noneMatch(line -> line.contains("unreachable") || line.contains(unreachable));
        mvc.perform(request(unreachable, "original", "club-a", "account-a"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("IN_PROGRESS"));
        clock.advance(IdempotencyRepository.CLAIM_LEASE.plusSeconds(1));
        mvc.perform(request(unreachable, "original", "club-a", "account-a")).andExpect(status().isCreated());
        assertThat(controller.calls).hasValue(4);
        assertThat(mongo.getCollection("idempotency_effects").countDocuments()).isEqualTo(2);
    }
    /** The failure that leaves the filter: MockMvc rethrows what no handler answered. */
    private Throwable surfaced(String key) {
        var thrown = catchThrowable(() -> mvc.perform(request(key, "original", "club-a", "account-a")));
        assertThat(thrown).isNotNull();
        while (thrown instanceof jakarta.servlet.ServletException && thrown.getCause() != null) { thrown = thrown.getCause(); }
        return thrown;
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
        /** A keyed PUT: its handler declares the header, as the contract's keyed PUTs do. */
        @PutMapping("/api/v1/test/idempotency/{id}") Map<String, Object> edit(@PathVariable String id, @RequestBody String body,
                @RequestHeader("Idempotency-Key") String key) {
            int count = calls.incrementAndGet();
            mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("value", body), "idempotency_effects");
            return Map.of("id", id, "body", body, "count", count);
        }
        @DeleteMapping("/api/v1/test/idempotency/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
        void remove(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
            calls.incrementAndGet();
            mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("value", "deleted " + id), "idempotency_effects");
        }
        /** A GET that even reads the header: never filtered. */
        @GetMapping("/api/v1/test/idempotency/{id}") Map<String, Object> read(@PathVariable String id,
                @RequestHeader(value = "Idempotency-Key", required = false) String key) {
            return Map.of("id", id, "count", calls.incrementAndGet());
        }
        /** A PUT that does not declare the header: not filtered, whatever the client sends. */
        @PutMapping("/api/v1/test/idempotency/{id}/plain") Map<String, Object> plain(@PathVariable String id, @RequestBody String body) {
            return Map.of("id", id, "count", calls.incrementAndGet());
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
