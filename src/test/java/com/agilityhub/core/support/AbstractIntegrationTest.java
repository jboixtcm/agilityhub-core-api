package com.agilityhub.core.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "shared.scheduling.enabled=false")
@Testcontainers
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
public abstract class AbstractIntegrationTest {

    // Singleton lifecycle: no @Container, which would stop Mongo after each subclass.
    // Testcontainers' Ryuk cleans up when the test JVM exits.
    protected static final String OIDC_MASTER = java.util.Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    // Fixtures use MockClock; Mongo's wall-clock TTL worker would delete their historical
    // tokens/events nondeterministically. Keep TTL indexes and application expiry checks.
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7")
            .withCommand("--replSet", "docker-rs", "--bind_ip_all", "--setParameter", "ttlMonitorEnabled=false");

    static {
        MONGO.start();
        // E5-T07: the repeated runs use -XX:ActiveProcessorCount=2 like the CI runner; the log shows what the JVM saw.
        System.out.println("Integration JVM availableProcessors=" + Runtime.getRuntime().availableProcessors());
    }

    @Autowired
    protected MockClock clock;

    @Autowired
    protected Fixtures fixtures;

    @Autowired
    private MongoTemplate sharedDatabase;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("core.oidc.master-key", () -> OIDC_MASTER);
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("agilityhub_test"));
    }

    @BeforeEach
    void resetClock() {
        clock.setInstant(IntegrationTestConfiguration.INITIAL_INSTANT);
    }

    /**
     * E5-T07: every cached test context shares the database, and {@code OutboxDispatcher.dispatch()} claims across
     * tenants, oldest {@code nextAttemptAt} first, at most 100 records per call. Events that earlier tests left
     * PENDING (they never dispatched, or their consumers failed and backed off) were claimed before this test's own,
     * and a test's few synchronous {@code dispatch()} calls could end without reaching them — how many depended on the
     * order the test classes ran in. Each test therefore starts with an empty outbox backlog (JUnit runs this superclass
     * method before the subclass fixtures publish anything).
     */
    @BeforeEach
    void discardOutboxBacklog() {
        // Counterfactual switch for the E5-T07 evidence only (-Dit.keepOutboxBacklog=true in the forked JVM's argLine).
        if (Boolean.getBoolean("it.keepOutboxBacklog")) { return; }
        long discarded = sharedDatabase.remove(Query.query(Criteria.where("status").is("PENDING")), "domain_events").getDeletedCount();
        if (discarded > OUTBOX_BATCH) {
            System.out.println("E5-T07 outbox backlog discarded before " + getClass().getSimpleName() + ": " + discarded + " PENDING records (more than one dispatch() batch)");
        }
    }

    private static final int OUTBOX_BATCH = 100;
}
