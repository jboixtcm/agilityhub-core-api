package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.migration.persistence.MigrationResetRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class TenantWriteTrackingIT extends AbstractIntegrationTest {
    private static final String CLUB = "write-fence-test", OTHER = "write-fence-other", COLLECTION = "write_fence_test_rows";
    @org.springframework.data.mongodb.core.mapping.Document(COLLECTION)
    record Row(@Id String id, String clubId, int value) implements TenantEntity { }
    @Autowired MongoTemplate mongo;
    @Autowired MigrationResetRepository reset;
    @Autowired TransactionTemplate transactions;

    @BeforeEach void prepare() {
        TenantContext.clear();
        mongo.remove(new Query(), COLLECTION);
        mongo.insert(new Row("existing", CLUB, 0));
        mongo.insert(new Row("other", OTHER, 0));
        checkpoint();
    }

    void checkpoint() {
        try (var tenant = TenantContext.open(CLUB)) {
            transactions.executeWithoutResult(status -> reset.checkpoint(true, clock.instant()));
        }
    }

    void check() {
        try (var tenant = TenantContext.open(CLUB)) {
            transactions.executeWithoutResult(status -> reset.requireNoLaterWrites());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert", "batch", "insertAll", "save", "update", "multi", "upsert", "modify", "modifyUpsert", "replace", "replaceUpsert", "delete", "removeOne", "removeAll"})
    void T_18_16_round2_point1_everyMutationWithoutAuditOrOutboxRefusesReset(String operation) {
        // A privileged worker has no TenantContext and may address a document by id only.
        Query id = Query.query(Criteria.where("_id").is("existing"));
        Update update = new Update().set("value", 1);
        long audits = mongo.count(new Query(), "audit_entries"), events = mongo.count(new Query(), "domain_events");
        switch (operation) {
            case "insert" -> mongo.insert(new Row("new", CLUB, 1));
            case "batch" -> mongo.insert(List.of(new Row("new", CLUB, 1)), Row.class);
            case "insertAll" -> mongo.insertAll(List.of(new Row("new", CLUB, 1)));
            case "save" -> mongo.save(new Row("existing", CLUB, 1));
            case "update" -> mongo.updateFirst(id, update, Row.class);
            case "multi" -> mongo.updateMulti(new Query(), update, COLLECTION);
            case "upsert" -> mongo.upsert(Query.query(Criteria.where("_id").is("new")), update.setOnInsert("clubId", CLUB), COLLECTION);
            case "modify" -> mongo.findAndModify(id, update, Row.class);
            case "modifyUpsert" -> mongo.findAndModify(Query.query(Criteria.where("_id").is("new")), update.setOnInsert("clubId", CLUB),
                    org.springframework.data.mongodb.core.FindAndModifyOptions.options().upsert(true), Row.class);
            case "replace" -> mongo.findAndReplace(id, new Row("existing", CLUB, 1));
            case "replaceUpsert" -> mongo.findAndReplace(Query.query(Criteria.where("_id").is("new")), new Row("new", CLUB, 1),
                    org.springframework.data.mongodb.core.FindAndReplaceOptions.options().upsert());
            case "delete" -> mongo.remove(id, Row.class);
            case "removeOne" -> mongo.findAndRemove(id, Row.class);
            case "removeAll" -> mongo.findAllAndRemove(id, Row.class);
            default -> throw new AssertionError(operation);
        }
        assertThatThrownBy(this::check).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
        assertThat(mongo.count(new Query(), "audit_entries")).isEqualTo(audits);
        assertThat(mongo.count(new Query(), "domain_events")).isEqualTo(events);
    }

    @Test void T_18_16_round2_point1_rollbackNoOpAndOtherTenantPermitReset() {
        transactions.executeWithoutResult(status -> {
            mongo.updateFirst(Query.query(Criteria.where("_id").is("existing")), new Update().set("value", 1), Row.class);
            status.setRollbackOnly();
        });
        assertThat(mongo.findById("existing", Row.class).value()).isZero();
        check();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("existing")), new Update().set("value", 0), Row.class);
        mongo.remove(Query.query(Criteria.where("_id").is("missing")), Row.class);
        check();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("other")), new Update().set("value", 1), Row.class);
        check();
        // A multi-tenant worker must fence every affected club, not whichever request scope happens to be open.
        mongo.updateMulti(new Query(), new Update().set("value", 2), Row.class);
        assertThatThrownBy(this::check).isInstanceOf(ApiException.class);
    }

    @Test void T_18_16_round2_point1_writerWithoutOuterTransactionRollsBackIfResetOwnsCounter() {
        try (var tenant = TenantContext.open(CLUB)) {
            transactions.executeWithoutResult(status -> {
                reset.requireNoLaterWrites();
                var outside = new TransactionTemplate(transactions.getTransactionManager());
                outside.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                assertThatThrownBy(() -> outside.execute(ignored -> mongo.updateFirst(
                        Query.query(Criteria.where("_id").is("existing")), new Update().set("value", 5), Row.class)))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
            });
        }
        assertThat(mongo.findById("existing", Row.class).value()).isZero();
        check();
    }

    @Test void T_18_16_round2_point1_nestedIndependentWritesCommitAndBlockResetThroughout() {
        var nested = new TransactionTemplate(transactions.getTransactionManager());
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.executeWithoutResult(status -> {
            mongo.updateFirst(Query.query(Criteria.where("_id").is("existing")), new Update().set("value", 1), Row.class);
            nested.executeWithoutResult(inner -> {
                mongo.insert(new Row("nested", CLUB, 2));
                assertThatThrownBy(() -> nested.executeWithoutResult(check -> check())).isInstanceOf(ApiException.class);
            });
            assertThat(mongo.findById("existing", Row.class).value()).isEqualTo(1);
        });
        assertThat(mongo.findById("nested", Row.class).value()).isEqualTo(2);
        assertThat(mongo.findById("existing", Row.class).value()).isEqualTo(1);
        assertThatThrownBy(this::check).isInstanceOf(ApiException.class);
        assertThat(mongo.findById(CLUB, TenantWriteCounterRepository.Counter.class).activeWriters()).isZero();
    }

    @Test void T_18_16_round2_point1_unscopedClaimCountsOnlyTheAffectedTenant() {
        var outside = new TransactionTemplate(transactions.getTransactionManager());
        outside.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        try (var tenant = TenantContext.open(CLUB)) {
            transactions.executeWithoutResult(status -> {
                reset.requireNoLaterWrites();
                // A total-order claim selects OTHER; maintenance on CLUB must not block it.
                outside.executeWithoutResult(ignored -> mongo.findAndModify(new Query().with(
                                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "_id")),
                        new Update().set("value", 3), org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), Row.class));
            });
        }
        assertThat(mongo.findById("other", Row.class).value()).isEqualTo(3);
        check();
    }

    @Test void T_18_16_round2_point1_legacyCheckpointsAndUnfinishedWritersFailClosed() {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().unset("writeTrackingVersion"), "migration_reset_guards");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().unset("activeWriters"), "tenant_write_counters");
        assertThatThrownBy(this::check).isInstanceOf(ApiException.class);
        checkpoint();
        // Simulate a process that registered its transaction but died before completion could run.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().inc("activeWriters", 1L), "tenant_write_counters");
        assertThatThrownBy(this::check).isInstanceOf(ApiException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().inc("activeWriters", -1L), "tenant_write_counters");
    }
}
