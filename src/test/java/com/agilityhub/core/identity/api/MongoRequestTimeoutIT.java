package com.agilityhub.core.identity.api;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E85: the HTTP deadline must not become the lifetime of a seed/job transaction. */
@Import(MongoRequestTimeoutIT.SlowOperation.class)
@TestPropertySource(properties = "core.mongo.operation-timeout=200ms")
class MongoRequestTimeoutIT extends IdentityIntegrationSupport {
    @Autowired SlowOperation work;

    @RestController static class SlowOperation {
        private final MongoTemplate mongo;
        private volatile boolean firstWrite;
        private volatile long committedCount;
        private volatile Long databaseTimeout;
        private volatile Long sessionTimeout;
        private final org.springframework.data.mongodb.MongoDatabaseFactory databaseFactory;
        private final TransactionTemplate transactions;
        SlowOperation(MongoTemplate mongo, TransactionTemplate transactions, org.springframework.data.mongodb.MongoDatabaseFactory databaseFactory) {
            this.mongo = mongo; this.transactions = transactions; this.databaseFactory = databaseFactory;
        }
        boolean firstWriteCompleted() { return firstWrite; }
        long committedCount() { return committedCount; }
        Long databaseTimeout() { return databaseTimeout; }
        Long sessionTimeout() { return sessionTimeout; }
        @GetMapping({"/api/v1/e11-timeout-probe", "/api/v1/e11-timeout-probe/export",
                "/api/v1/e11-timeout-probe/data-export", "/api/v1/exports/e11-timeout-probe/download",
                "/api/v1/jobs/e11-timeout-probe/trigger", "/api/v1/platform/clubs/club-a/jobs/e11-timeout-probe/trigger"})
        long execute() {
            firstWrite = false; committedCount = -1;
            databaseTimeout = mongo.getDb().getTimeout(java.util.concurrent.TimeUnit.MILLISECONDS);
            try (var session = databaseFactory.getSession(com.mongodb.ClientSessionOptions.builder().build())) {
                sessionTimeout = session.getOptions().getDefaultTimeout(java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "e11_timeout_fixture");
            return transactions.execute(status -> {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() {
                                committedCount = mongo.getCollection("e11_timeout_fixture").countDocuments();
                            }
                        });
                mongo.insert(new Document("clubId", "club-a").append("part", 1), "e11_timeout_fixture");
                firstWrite = true;
                try { Thread.sleep(450); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                mongo.insert(new Document("clubId", "club-a").append("part", 2), "e11_timeout_fixture");
                return mongo.getCollection("e11_timeout_fixture").countDocuments();
            });
        }
    }

    @Test void T_08_40_E85_backgroundTransactionCanOutliveTheHttpBudget() {
        assertThat(work.execute()).isEqualTo(2);
        assertThat(work.databaseTimeout()).isNull();
        assertThat(work.sessionTimeout()).isNull();
    }

    @Test void T_08_40_E85_httpIsBoundedButExportsAndManualJobsFollowTheBackgroundRule() throws Exception {
        var identity = jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"));
        mvc.perform(get("/api/v1/e11-timeout-probe").header("Host", HOST).with(identity))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(work.firstWriteCompleted()).as("the deadline expires during the transaction, after the first write").isTrue();
        assertThat(work.databaseTimeout()).isEqualTo(200);
        assertThat(work.sessionTimeout()).isEqualTo(200);
        for (String path : new String[]{"/api/v1/e11-timeout-probe/export", "/api/v1/e11-timeout-probe/data-export",
                "/api/v1/exports/e11-timeout-probe/download", "/api/v1/jobs/e11-timeout-probe/trigger",
                "/api/v1/platform/clubs/club-a/jobs/e11-timeout-probe/trigger"}) {
            mvc.perform(get(path).header("Host", HOST).with(identity))
                    .andExpect(status().isOk()).andExpect(content().string("2"));
            assertThat(work.committedCount()).as("after-commit consumers can read within the session budget").isEqualTo(2);
            assertThat(work.databaseTimeout()).as("a legitimate export/manual job has no HTTP operation deadline").isNull();
            assertThat(work.sessionTimeout()).as("a manually triggered job follows the scheduled-job transaction rule").isNull();
        }
        assertThat(work.execute()).as("the servlet thread does not retain its HTTP deadline").isEqualTo(2);
    }
}
