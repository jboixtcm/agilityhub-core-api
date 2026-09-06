package com.agilityhub.core.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start();
    }

    @Autowired
    protected MockClock clock;

    @Autowired
    protected Fixtures fixtures;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("core.oidc.master-key", () -> OIDC_MASTER);
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("agilityhub_test"));
    }

    @BeforeEach
    void resetClock() {
        clock.setInstant(IntegrationTestConfiguration.INITIAL_INSTANT);
    }
}
