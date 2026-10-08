package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.application.TenantContext;
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
    public MigrationResetRepository(MongoTemplate mongo) { super(mongo, MigrationRun.class); }
    /** Taken after the load's completion transaction: its own audit entry and outbox events are not later than {@code loadedAt}. */
    public void checkpoint(boolean production, Instant loadedAt) {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())),
                new Update().set("clubId", TenantContext.require()).set("loadedAt", loadedAt).set("production", production), GUARDS);
    }
    /**
     * T-18-16 (R-18-14): after a production load, the club's later writes — its audit entries and outbox events after the load,
     * other than the migration's own — are counted, and any of them refuses the reset. Writes that bypass the api (and so
     * leave neither) are not counted.
     */
    public void requireNoLaterWrites() {
        var guard = mongo.findOne(tenantQuery(), Document.class, GUARDS);
        if (guard == null || !Boolean.TRUE.equals(guard.get("production"))) { return; }
        var loadedAt = guard.getDate("loadedAt").toInstant();
        long audits = mongo.count(tenantQuery().addCriteria(Criteria.where("at").gt(loadedAt).and("action").ne("MIGRATION_APPLIED")), "audit_entries");
        long events = mongo.count(tenantQuery().addCriteria(Criteria.where("occurredAt").gt(loadedAt).and("type").not().regex("^MigrationRun")), "domain_events");
        if (audits + events > 0) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
    }
    /** Audit history survives reset; global identity data is outside this tenant boundary; Mongo's own `system.*` are not data. */
    public Set<String> collections() {
        var names = new TreeSet<>(mongo.getCollectionNames()); names.removeIf(name -> name.startsWith("system.")); return names;
    }
    public void erase(Set<String> collections) {
        for (String collection : collections) {
            if (!Set.of("clubs", "audit_entries").contains(collection)) { mongo.remove(tenantQuery(), collection); }
        }
    }
}
