package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class OutboxIT extends AbstractIntegrationTest {
    @Autowired MongoTemplate mongo;
    @Autowired MongoTransactionManager manager;
    @Autowired EventPublisher publisher;
    @Autowired OutboxRepository records;
    @Autowired ObjectMapper mapper;
    TransactionTemplate transactions;
    SimpleMeterRegistry metrics;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2030-01-01T00:00:00Z"));
        transactions = new TransactionTemplate(manager);
        metrics = new SimpleMeterRegistry();
        mongo.remove(new Query(), DomainEventRecord.class);
        for (String name : new String[] {"outbox_aggregates", "outbox_effects"}) {
            if (!mongo.collectionExists(name)) { mongo.createCollection(name); }
            mongo.getCollection(name).deleteMany(new Document());
        }
    }
    private ClubConfigChanged event(String clubId) {
        return new ClubConfigChanged(clubId, clock.instant(), Map.of("diff", Map.of("name", "Example Club")),
                "account-a", null, DomainEvent.Origin.BACKOFFICE);
    }
    private String publish(String clubId) {
        return transactions.execute(status -> {
            mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("clubId", clubId), "outbox_aggregates");
            return publisher.publish(event(clubId));
        });
    }
    private DomainEventRecord record(String id) { return mongo.findById(id, DomainEventRecord.class); }
    private OutboxDispatcher dispatcher(Map<String, DomainEventHandler<?>> handlers, int maxAttempts) {
        return new OutboxDispatcher(records, handlers, transactions, mapper, clock, metrics, maxAttempts);
    }
    private DomainEventHandler<ClubConfigChanged> handler(Delivery delivery) {
        return new DomainEventHandler<>() {
            public String eventType() { return "ClubUpdated"; }
            public Class<ClubConfigChanged> eventClass() { return ClubConfigChanged.class; }
            public void handle(String id, ClubConfigChanged event) throws Exception { delivery.accept(id, event); }
        };
    }
    interface Delivery { void accept(String id, ClubConfigChanged event) throws Exception; }

    @Test void E0_T04_failedAggregateTransactionLeavesNoOutboxRecord() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            mongo.insert(new Document("clubId", "club-a"), "outbox_aggregates");
            publisher.publish(event("club-a"));
            assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(1);
            throw new IllegalStateException("Rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(mongo.findAll(DomainEventRecord.class)).isEmpty();
        assertThat(mongo.getCollection("outbox_aggregates").countDocuments()).isZero();
        assertThatThrownBy(() -> publisher.publish(event("club-a"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mongo transaction");
    }
    @Test void E0_T04_deliversToEveryMatchingConsumerAndPublishesWithRetentionIndexes() {
        String id = publish("club-a");
        AtomicInteger calls = new AtomicInteger();
        var consumer = handler((eventId, event) -> {
            assertThat(eventId).isEqualTo(id);
            assertThat(event).isEqualTo(event("club-a"));
            calls.incrementAndGet();
        });
        var unrelated = new DomainEventHandler<ClubConfigChanged>() {
            public String eventType() { return "ParameterChanged"; }
            public Class<ClubConfigChanged> eventClass() { return ClubConfigChanged.class; }
            public void handle(String ignored, ClubConfigChanged event) { fail("Unrelated consumer invoked"); }
        };
        var dispatcher = dispatcher(Map.of("one", consumer, "two.with.dot", consumer, "unrelated", unrelated), 10);
        assertThat(metrics.get("outbox.pending").gauge().value()).isEqualTo(1);
        dispatcher.dispatch(); dispatcher.dispatch();
        assertThat(calls).hasValue(2);
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.PUBLISHED);
        assertThat(record(id).attempts()).isEqualTo(1);
        assertThat(record(id).publishedAt()).isEqualTo(clock.instant());
        assertThat(record(id).processedAt()).hasSize(2);
        assertThat(record(id).claimToken()).isNull();
        assertThat(metrics.get("outbox.pending").gauge().value()).isZero();
        assertThat(metrics.get("outbox.failed").gauge().value()).isZero();
        var indexes = mongo.getCollection("domain_events").listIndexes().into(new java.util.ArrayList<>());
        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.getString("name")).isEqualTo("outbox_retention");
            assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isEqualTo(90 * 86400L);
            assertThat(index.get("key", Document.class)).containsEntry("publishedAt", 1);
        });
        System.out.println("db.domain_events.getIndexes(): " + indexes);
    }
    @Test void E0_T04_retryRollsBackHandlerWritesAndSkipsAlreadyProcessedConsumers() {
        String id = publish("club-a");
        AtomicInteger first = new AtomicInteger(); AtomicInteger second = new AtomicInteger();
        Map<String, DomainEventHandler<?>> handlers = new LinkedHashMap<>();
        handlers.put("first", handler((eventId, event) -> first.incrementAndGet()));
        handlers.put("second", handler((eventId, event) -> {
            mongo.insert(new Document("_id", eventId).append("clubId", event.clubId()), "outbox_effects");
            if (second.incrementAndGet() == 1) { throw new IllegalArgumentException("Handler failure"); }
        }));
        var dispatcher = dispatcher(handlers, 10);
        dispatcher.dispatch();
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.PENDING);
        assertThat(record(id).processedAt()).hasSize(1);
        assertThat(record(id).nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(1));
        assertThat(mongo.getCollection("outbox_effects").countDocuments()).isZero();
        dispatcher.dispatch(); assertThat(second).hasValue(1);
        clock.advance(Duration.ofSeconds(1)); dispatcher.dispatch();
        assertThat(first).hasValue(1); assertThat(second).hasValue(2);
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.PUBLISHED);
        assertThat(record(id).error()).isNull();
        assertThat(mongo.getCollection("outbox_effects").countDocuments()).isEqualTo(1);
    }
    @Test void E0_T04_maxAttemptsFailsAndBackoffCapsAtFiveMinutes() {
        String id = publish("club-a");
        var failing = handler((ignored, event) -> { throw new IllegalStateException("private data must not be persisted"); });
        var dispatcher = dispatcher(Map.of("failing", failing), 2);
        dispatcher.dispatch(); clock.advance(Duration.ofSeconds(1)); dispatcher.dispatch();
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.FAILED);
        assertThat(record(id).attempts()).isEqualTo(2);
        assertThat(record(id).publishedAt()).isNull();
        assertThat(record(id).error()).isEqualTo("IllegalStateException");
        assertThat(metrics.get("outbox.failed").gauge().value()).isEqualTo(1);
        clock.advance(Duration.ofDays(1)); dispatcher.dispatch();
        assertThat(record(id).attempts()).isEqualTo(2);
        String other = publish("club-b");
        dispatcher = dispatcher(Map.of("failing", failing), 11);
        for (int attempt = 1; attempt <= 10; attempt++) {
            dispatcher.dispatch();
            long delay = Math.min(300, 1L << (attempt - 1));
            assertThat(record(other).nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(delay));
            clock.advance(Duration.ofSeconds(delay));
        }
        assertThatThrownBy(() -> dispatcher(Map.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void E0_T04_competingDispatchersDoNotDeliverAnActiveClaimTwice() throws Exception {
        String id = publish("club-a");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var consumer = handler((ignored, event) -> {
            calls.incrementAndGet(); entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test release timed out"); }
        });
        var one = dispatcher(Map.of("consumer", consumer), 10);
        var two = dispatcher(Map.of("consumer", consumer), 10);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(one::dispatch);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                clock.advance(OutboxRepository.LEASE);
                var competing = pool.submit(two::dispatch);
                try { competing.get(1, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException waitingForWriteLock) {
                    // A nontransactional claim may wait for the active handler transaction's write lock.
                }
                assertThat(calls).hasValue(1);
                release.countDown();
                competing.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.PUBLISHED);
    }
    @Test void E0_T04_expiredClaimRecoversAndOldOwnerCannotFinish() {
        String id = publish("club-a");
        var old = records.claim(clock.instant());
        clock.advance(OutboxRepository.LEASE);
        var replacement = records.claim(clock.instant());
        assertThat(replacement.id()).isEqualTo(id);
        assertThat(replacement.claimToken()).isNotEqualTo(old.claimToken());
        assertThatThrownBy(() -> records.published(old, clock.instant())).isInstanceOf(IllegalStateException.class);
        records.published(replacement, clock.instant());
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.PUBLISHED);
    }
    @Test void E0_T04_batchIsLimitedToOneHundredAndNoConsumersIsValid() {
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 101; i++) { publisher.publish(event("club-a")); }
        });
        var dispatcher = dispatcher(Map.of(), 10);
        dispatcher.dispatch();
        assertThat(records.count(DomainEventRecord.Status.PUBLISHED)).isEqualTo(100);
        assertThat(records.count(DomainEventRecord.Status.PENDING)).isEqualTo(1);
        dispatcher.dispatch();
        assertThat(records.count(DomainEventRecord.Status.PUBLISHED)).isEqualTo(101);
    }
    @Test void E0_T04_crashedClaimsCannotRetryForever() {
        String id = publish("club-a");
        records.claim(clock.instant());
        clock.advance(OutboxRepository.LEASE);
        dispatcher(Map.of(), 1).dispatch();
        assertThat(record(id).status()).isEqualTo(DomainEventRecord.Status.FAILED);
    }
}
