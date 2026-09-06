package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

@Import(AuditedAspectIT.AuditTestConfiguration.class)
class AuditedAspectIT extends AbstractIntegrationTest {
    @Autowired MongoTemplate mongo;
    @Autowired MongoClient mongoClient;
    @Autowired MongoTransactionManager transactions;
    @Autowired FakeService service;
    @Autowired ParameterStore parameters;
    @Autowired AuditWriter writer;
    @Autowired AuditQuery query;
    @Autowired AuditRepository repository;
    @Autowired TestActors actors;

    @BeforeEach void seed() {
        TenantContext.clear();
        mongo.remove(new Query(), AuditEntry.class);
        mongo.remove(new Query(), "domain_events");
        mongo.dropCollection(AuditableParameter.class);
        mongo.createCollection(AuditableParameter.class);
        try (var scope = TenantContext.open("club-a")) {
            parameters.insert(new AuditableParameter("parameter-a", "club-a", "before", "ES0000002231"));
        }
        actors.actor.set(new AuditActor("account-a", "Example Admin", "ADMIN", "member-impersonated", true,
                "192.0.2.1", "Audit test client", "trace-a"));
    }

    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test
    @AuditCovers(AuditAction.PARAMETER_CHANGED)
    void T_14_12_parameterEntryCommitsAtomicallyWithAggregateAndOutboxAndCapturesMetadata() {
        Instant writtenAt = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(writtenAt);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            try (var scope = TenantContext.open("club-a")) {
                var result = service.update("parameter-a", "after", false);
                assertThat(result.value()).isEqualTo("after");
                assertThat(entries()).hasSize(1);
                assertThat(outboxEntries()).hasSize(1);
                assertThat(parameters.findById("parameter-a").orElseThrow().value()).isEqualTo("after");
                assertThat(committedCollection("audit_entries").countDocuments()).isZero();
                assertThat(committedCollection("domain_events").countDocuments()).isZero();
                assertThat(committedCollection("audit_test_parameters").find(new Document("_id", "parameter-a"))
                        .first().getString("value")).isEqualTo("before");
            }
            actors.actor.set(new AuditActor("other", "Other", "SYSTEM", null, false, null, null, "other"));
            clock.setInstant(Instant.parse("2026-01-01T01:00:00Z"));
        });
        assertThat(TenantContext.current()).isNull();
        assertThat(outboxEntries()).singleElement().satisfies(event -> {
            assertThat(event.getString("type")).isEqualTo("ClubUpdated");
            assertThat(event.getString("clubId")).isEqualTo("club-a");
        });
        try (var scope = TenantContext.open("club-a")) {
            assertThat(parameters.findById("parameter-a").orElseThrow().value()).isEqualTo("after");
        }
        assertThat(entries()).singleElement().satisfies(entry -> {
            assertThat(entry.clubId()).isEqualTo("club-a");
            assertThat(entry.action()).isEqualTo(AuditAction.PARAMETER_CHANGED);
            assertThat(entry.entityType()).isEqualTo("Parameter");
            assertThat(entry.entityId()).isEqualTo("parameter-a");
            assertThat(entry.memberId()).isEqualTo("member-a");
            assertThat(entry.actorAccountId()).isEqualTo("account-a");
            assertThat(entry.actorName()).isEqualTo("Example Admin");
            assertThat(entry.actorRole()).isEqualTo("ADMIN");
            assertThat(entry.impersonatedMemberId()).isEqualTo("member-impersonated");
            assertThat(entry.support()).isTrue();
            assertThat(entry.ip()).isEqualTo("192.0.2.1");
            assertThat(entry.userAgent()).isEqualTo("Audit test client");
            assertThat(entry.traceId()).isEqualTo("trace-a");
            assertThat(entry.at()).isEqualTo(writtenAt);
            assertThat(entry.changes()).containsExactly(new AuditChange("value", "before", "after"));
        });
    }

    @Test void T_14_12_transactionalMethodCommitsAndServiceFailureRollsBack() {
        try (var scope = TenantContext.open("club-a")) {
            service.update("parameter-a", "committed", false);
            assertThat(entries()).hasSize(1);
            assertThat(outboxEntries()).hasSize(1);
            assertThatThrownBy(() -> service.update("parameter-a", "failed", true)).hasMessage("Forced service rollback");
            assertThat(entries()).hasSize(1);
            assertThat(outboxEntries()).hasSize(1);
            assertThat(parameters.findById("parameter-a").orElseThrow().value()).isEqualTo("committed");
        }
    }

    @Test void T_14_12_rollbackOnlyAndExceptionAfterAuditedMethodLeaveNoEntries() {
        try (var scope = TenantContext.open("club-a")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.update("parameter-a", "rolled-back", false);
                assertThat(entries()).hasSize(1);
                assertThat(outboxEntries()).hasSize(1);
                status.setRollbackOnly();
            });
            assertThat(entries()).isEmpty();
            assertThat(outboxEntries()).isEmpty();
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.update("parameter-a", "also-rolled-back", false);
                assertThat(entries()).hasSize(1);
                assertThat(outboxEntries()).hasSize(1);
                throw new IllegalStateException("Forced outer rollback");
            })).hasMessage("Forced outer rollback");
            assertThat(entries()).isEmpty();
            assertThat(outboxEntries()).isEmpty();
            assertThat(parameters.findById("parameter-a").orElseThrow().value()).isEqualTo("before");
        }
    }

    @Test void T_14_12_auditInsertFailureRollsBackAggregateAndOutbox() {
        mongo.executeCommand(new Document("collMod", "audit_entries")
                .append("validator", new Document("action", new Document("$ne", "PARAMETER_CHANGED"))));
        try (var scope = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> service.update("parameter-a", "must-roll-back", false))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("Document failed validation");
            assertThat(parameters.findById("parameter-a").orElseThrow().value()).isEqualTo("before");
            assertThat(entries()).isEmpty();
            assertThat(outboxEntries()).isEmpty();
        } finally {
            mongo.executeCommand(new Document("collMod", "audit_entries").append("validator", new Document()));
        }
    }

    @Test void T_14_12_manualWriterParticipatesInRollback() {
        try (var scope = TenantContext.open("club-a")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                writer.write(command(AuditAction.CLUB_UPDATED, "club-a", "club:apply"));
                assertThat(entries()).hasSize(1);
                assertThat(committedCollection("audit_entries").countDocuments()).isZero();
                status.setRollbackOnly();
            });
        }
        assertThat(entries()).isEmpty();
    }

    @Test void T_14_07_aspectSkipsNoOpAndWritesImmediatelyWithoutTransaction() {
        try (var scope = TenantContext.open("club-a")) {
            service.update("parameter-a", "before", false);
            assertThat(entries()).isEmpty();
            service.withoutTransaction("parameter-a", "immediate");
            assertThat(entries()).singleElement().satisfies(entry ->
                    assertThat(entry.changes()).containsExactly(new AuditChange("value", "before", "immediate")));
        }
    }

    @Test void T_14_12_resultExpressionsSupportCreationAndLoadersSupportDeletion() {
        try (var scope = TenantContext.open("club-a")) {
            service.create("new-parameter");
            assertThat(query.lastChange("Parameter", "new-parameter").action()).isEqualTo(AuditAction.PARAMETER_CHANGED);
            service.delete("new-parameter");
            assertThat(entries()).hasSize(2);
            assertThat(entries().stream().flatMap(entry -> entry.changes().stream()))
                    .contains(new AuditChange("value", null, "created"), new AuditChange("value", "created", null));
        }
    }

    @Test
    @AuditCovers(AuditAction.CLUB_UPDATED)
    void T_14_12_manualWriterMasksAndFreezesMutableSnapshotsBeforeCommit() {
        var values = new java.util.ArrayList<>(List.of("old"));
        record ClubState(@AuditField List<String> modules, @AuditField @Sensitive String iban) { }
        try (var scope = TenantContext.open("club-a")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                writer.write(new AuditCommand(AuditAction.CLUB_UPDATED, "Club", "club-a", null, null,
                        new ClubState(values, "ES0000002231"), null));
                values.add("must-not-appear");
                assertThat(entries()).hasSize(1);
            });
        }
        assertThat(entries()).singleElement().satisfies(entry -> {
            assertThat(entry.changes()).containsExactly(new AuditChange("modules", null, List.of("old")),
                    new AuditChange("iban", null, "···· ···· ···· ···· 2231"));
            assertThat(entry.toString()).doesNotContain("ES000", "must-not-appear");
        });
    }

    @Test
    @AuditCovers(AuditAction.CLUB_MODULES_CHANGED)
    void T_14_11_lastChangeUsesLatestTimestampAndSeparatesTenantsAndTargets() {
        try (var scope = TenantContext.open("club-a")) {
            assertThat(query.lastChange("Club", "same-id")).isNull();
            writer.write(command(AuditAction.CLUB_UPDATED, "same-id", "first"));
            clock.setInstant(Instant.parse("2026-01-02T00:00:00Z"));
            writer.write(command(AuditAction.CLUB_MODULES_CHANGED, "same-id", "modules"));
            assertThat(query.lastChange("Club", "same-id")).isEqualTo(new AuditQuery.LastChange(clock.instant(), "Example Admin", AuditAction.CLUB_MODULES_CHANGED));
            assertThat(query.lastChange("Parameter", "same-id")).isNull();
            assertThat(query.lastChange("Club", "unknown")).isNull();
            assertThatThrownBy(() -> query.lastPlatformChange("Club", "same-id")).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
        }
        try (var scope = TenantContext.open("club-b")) {
            assertThat(query.lastChange("Club", "same-id")).isNull();
            writer.write(command(AuditAction.CLUB_UPDATED, "same-id", "other club"));
            assertThat(query.lastChange("Club", "same-id").action()).isEqualTo(AuditAction.CLUB_UPDATED);
        }
        assertThatThrownBy(() -> query.lastChange("Club", "same-id")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.NO_MEMBERSHIP));
        assertThat(query.lastPlatformChange("Club", "same-id")).isNull();
    }

    @Test
    @AuditCovers(AuditAction.CLUB_STATUS_CHANGED)
    void T_14_11_platformEntriesHaveNullClubIdAndExplicitIsolatedQuery() {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                writer.write(command(AuditAction.CLUB_STATUS_CHANGED, "club-a", "platform status change")));
        assertThat(entries()).singleElement().satisfies(entry -> assertThat(entry.clubId()).isNull());
        assertThat(query.lastPlatformChange("Club", "club-a").action()).isEqualTo(AuditAction.CLUB_STATUS_CHANGED);
        try (var scope = TenantContext.open("club-a")) {
            assertThat(query.lastChange("Club", "club-a")).isNull();
            assertThatThrownBy(() -> repository.append(entries().getFirst())).isInstanceOf(ApiException.class);
        }
    }

    @Test void T_14_12_repositoryRejectsCrossTenantInsertAndDuplicateIds() {
        try (var scope = TenantContext.open("club-a")) { writer.write(command(AuditAction.CLUB_UPDATED, "club-a", "change")); }
        AuditEntry entry = entries().getFirst();
        try (var scope = TenantContext.open("club-b")) {
            assertThatThrownBy(() -> repository.append(entry)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
        }
        try (var scope = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> repository.append(entry)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }
        assertThat(entries()).hasSize(1);
    }

    @Test void T_14_07_manualNoOpIsSkippedAndReasonOnlyEntryIsKept() {
        writer.write(command(AuditAction.CLUB_UPDATED, "club-a", null));
        writer.write(command(AuditAction.CLUB_UPDATED, "club-a", " "));
        assertThat(entries()).isEmpty();
        writer.write(command(AuditAction.CLUB_UPDATED, "club-a", "Configuration reapplied"));
        assertThat(entries()).singleElement().satisfies(entry -> {
            assertThat(entry.changes()).isEmpty();
            assertThat(entry.reason()).isEqualTo("Configuration reapplied");
        });
        assertThatThrownBy(() -> writer.write(command(null, "id", "reason"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writer.write(command(AuditAction.CLUB_UPDATED, null, "reason"))).hasMessage("Audit entity id is required");
        assertThatThrownBy(() -> writer.write(command(AuditAction.CLUB_UPDATED, " ", "reason"))).hasMessage("Audit entity id is required");
        assertThatThrownBy(() -> writer.write(new AuditCommand(AuditAction.CLUB_UPDATED, null, "id", null, null, null, "reason")))
                .hasMessage("Audit entity type is required");
    }

    @Test void T_14_11_requiredIndexesExistAtStartupWithoutTtl() {
        var indexes = mongo.getCollection("audit_entries").listIndexes().into(new java.util.ArrayList<>());
        assertThat(indexes).extracting(index -> index.get("key")).contains(
                new Document("clubId", 1).append("at", -1),
                new Document("clubId", 1).append("memberId", 1).append("at", -1),
                new Document("clubId", 1).append("entityType", 1).append("entityId", 1).append("at", -1));
        assertThat(indexes).noneMatch(index -> index.containsKey("expireAfterSeconds"));
        assertThat(indexes).noneMatch(index -> {
            var keys = index.get("key", Document.class);
            return keys.containsKey("targetType") || keys.containsKey("targetId");
        });
        System.out.println("audit_entries indexes: " + indexes);
    }

    @Test void T_14_07_persistedAuditUsesEntityNamesAndChangePaths() {
        try (var scope = TenantContext.open("club-a")) {
            service.update("parameter-a", "after", false);
        }
        Document entry = mongo.getCollection("audit_entries").find().first();
        assertThat(entry).containsEntry("entityType", "Parameter").containsEntry("entityId", "parameter-a")
                .doesNotContainKeys("targetType", "targetId");
        assertThat(entry.getList("changes", Document.class)).singleElement().satisfies(change ->
                assertThat(change).containsOnlyKeys("path", "before", "after")
                        .containsEntry("path", "value").containsEntry("before", "before").containsEntry("after", "after"));
    }

    private AuditCommand command(AuditAction action, String id, String reason) {
        return new AuditCommand(action, "Club", id, null, null, null, reason);
    }
    private List<AuditEntry> entries() { return mongo.findAll(AuditEntry.class); }
    private List<Document> outboxEntries() { return mongo.findAll(Document.class, "domain_events"); }
    private MongoCollection<Document> committedCollection(String name) {
        // MongoClient bypasses MongoTemplate's bound transaction session for independent observer reads.
        return mongoClient.getDatabase(mongo.getDb().getName()).getCollection(name);
    }

    @org.springframework.data.mongodb.core.mapping.Document("audit_test_parameters")
    public record AuditableParameter(@Id String id, String clubId, @AuditField String value,
                                     @AuditField @Sensitive String iban) implements TenantEntity {
        public String type() { return "Parameter"; }
    }

    static class ParameterStore extends TenantRepository<AuditableParameter> implements AuditableLoader {
        ParameterStore(MongoTemplate mongo) { super(mongo, AuditableParameter.class); }
        @Override public String entityType() { return "Parameter"; }
        @Override public Object load(String id) { return findById(id).orElse(null); }
    }

    static class FakeService {
        private final ParameterStore parameters;
        private final EventPublisher events;
        private final Clock clock;
        FakeService(ParameterStore parameters, EventPublisher events, Clock clock) {
            this.parameters = parameters;
            this.events = events;
            this.clock = clock;
        }

        @Transactional
        @Audited(action = AuditAction.PARAMETER_CHANGED, entityType = "'Parameter'", entity = "#id", member = "'member-a'")
        public AuditableParameter update(String id, String value, boolean fail) {
            var result = replace(id, value);
            events.publish(new ClubConfigChanged(result.clubId(), clock.instant(), Map.of("diff", Map.of(id, value)),
                    "account-a", "member-impersonated", DomainEvent.Origin.BACKOFFICE));
            if (fail) { throw new IllegalStateException("Forced service rollback"); }
            return result;
        }

        @Audited(action = AuditAction.PARAMETER_CHANGED, entityType = "'Parameter'", entity = "#p0")
        public AuditableParameter withoutTransaction(String id, String value) { return replace(id, value); }

        @Transactional
        @Audited(action = AuditAction.PARAMETER_CHANGED, entityType = "#result.type")
        public AuditableParameter create(String id) {
            return parameters.insert(new AuditableParameter(id, TenantContext.require(), "created", null));
        }

        @Transactional
        @Audited(action = AuditAction.PARAMETER_CHANGED, entityType = "'Parameter'", entity = "#id")
        public void delete(String id) { parameters.deleteById(id); }

        private AuditableParameter replace(String id, String value) {
            var before = parameters.findById(id).orElseThrow();
            return parameters.replace(new AuditableParameter(id, before.clubId(), value, before.iban()));
        }
    }

    static class TestActors implements AuditActorProvider {
        final AtomicReference<AuditActor> actor = new AtomicReference<>();
        @Override public AuditActor current() { return actor.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuditTestConfiguration {
        @Bean ParameterStore auditParameterStore(MongoTemplate mongo) { return new ParameterStore(mongo); }
        @Bean FakeService auditFakeService(ParameterStore store, EventPublisher events, Clock clock) {
            return new FakeService(store, events, clock);
        }
        @Bean @Primary TestActors auditTestActors() { return new TestActors(); }
    }
}
