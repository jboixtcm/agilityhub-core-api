package com.agilityhub.core.support;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** A second subclass exercises the shared container with a cached Spring context. */
class IntegrationTestSupportIT extends AbstractIntegrationTest {

    @Autowired
    private Clock injectedClock;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void E0_T02_sharedMongoRemainsPrimaryAcrossTestClasses() {
        assertThat(MONGO.isRunning()).isTrue();
        assertThat(mongoTemplate.executeCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))
                .isTrue();
        assertThat(mongoTemplate.getDb().getName()).isEqualTo("agilityhub_test");
    }

    @Test
    void E0_T02_injectedClockUsesResettableTestTime() {
        assertThat(injectedClock).isSameAs(clock);
        assertThat(injectedClock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(injectedClock.instant()).isEqualTo(IntegrationTestConfiguration.INITIAL_INSTANT);
        clock.advance(Duration.ofMinutes(30));
        assertThat(injectedClock.instant()).isEqualTo(IntegrationTestConfiguration.INITIAL_INSTANT.plusSeconds(1800));
    }
}
