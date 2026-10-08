package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantWriteFence;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Destructive S18 staging boundary. No document values or hashes enter the reset guard. */
@Repository
public class MigrationResetRepository extends TenantRepository<MigrationRun> {
    private static final String GUARDS = "migration_reset_guards";
    private final TenantWriteFence writes;
    public MigrationResetRepository(MongoTemplate mongo, TenantWriteFence writes) { super(mongo, MigrationRun.class); this.writes = writes; }
    public void trackWrites() { writes.lock(); }

    /** Completion, its audit/outbox and this counter checkpoint commit atomically. */
    public void checkpoint(boolean production, Instant loadedAt) {
        long sequence = writes.lock();
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())),
                new Update().set("clubId", TenantContext.require()).set("loadedAt", loadedAt)
                        .set("production", production).set("writeSequence", sequence), GUARDS);
    }

    /** T-18-16: the counter check and erase hold one transaction lock shared with audited/outbox writers. */
    public void requireNoLaterWrites() {
        long sequence = writes.lock();
        var guard = mongo.findOne(tenantQuery(), Document.class, GUARDS);
        if (guard == null || !Boolean.TRUE.equals(guard.get("production"))) { return; }
        // A legacy checkpoint cannot prove the absence of later writes; refuse destructively resetting it.
        if (!(guard.get("writeSequence") instanceof Number expected) || expected.longValue() != sequence) {
            throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED);
        }
    }
    /** Audit history survives reset; global identity data is outside this tenant boundary; Mongo's own `system.*` are not data. */
    public Set<String> collections() {
        var names = new TreeSet<>(mongo.getCollectionNames()); names.removeIf(name -> name.startsWith("system.")); return names;
    }
    public void erase(Set<String> collections) {
        for (String collection : collections) {
            if (!Set.of("clubs", "audit_entries", "tenant_write_counters").contains(collection)) { mongo.remove(tenantQuery(), collection); }
        }
    }
}
