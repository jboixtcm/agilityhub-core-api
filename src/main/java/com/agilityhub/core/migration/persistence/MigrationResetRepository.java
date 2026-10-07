package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Destructive S18 staging boundary. No document values or hashes enter the reset guard. */
@Repository
public class MigrationResetRepository extends TenantRepository<MigrationRun> {
    private static final String GUARDS = "migration_reset_guards";
    public MigrationResetRepository(MongoTemplate mongo) { super(mongo, MigrationRun.class); }
    public void checkpoint(boolean production) {
        // The server timestamp follows the completion transaction, including its audit and outbox writes.
        var time = mongo.executeCommand(new Document("ping", 1)).get("operationTime", BsonTimestamp.class);
        if (time == null) { throw new ApiException(ErrorCode.INVALID_STATE); }
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())),
                new Update().set("clubId", TenantContext.require()).set("timestamp", time).set("production", production), GUARDS);
    }
    public void requireNoLaterWrites() {
        var guard = mongo.findOne(tenantQuery(), Document.class, GUARDS);
        if (guard == null || !Boolean.TRUE.equals(guard.get("production"))) { return; }
        var time = guard.get("timestamp", BsonTimestamp.class);
        // Fail closed even for unrelated database writes: a delete event need not carry clubId. This also
        // catches writes made outside the API, which an application-only counter would miss.
        try (var changes = mongo.getDb().watch(List.of(Aggregates.match(Filters.and(
                Filters.gt("clusterTime", time), Filters.ne("ns.coll", GUARDS)))))
                .startAtOperationTime(time).maxAwaitTime(100, TimeUnit.MILLISECONDS).cursor()) {
            if (changes.tryNext() != null) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
        } catch (com.mongodb.MongoException unavailableHistory) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
    }
    /** Audit history survives reset; global identity data is outside this tenant boundary. */
    public Set<String> collections() { return mongo.getCollectionNames(); }
    public void erase(Set<String> collections) {
        for (String collection : collections) {
            if (!Set.of("clubs", "audit_entries").contains(collection)) { mongo.remove(tenantQuery(), collection); }
        }
    }
}
