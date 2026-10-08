package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class MigrationRunRepository extends TenantRepository<MigrationRun> {
    private static final String LOCKS = "migration_write_locks";
    private static final Duration LEASE = Duration.ofMinutes(10);
    public MigrationRunRepository(MongoTemplate mongo) { super(mongo, MigrationRun.class); }

    /** Claim outside a transaction. An expired process can never write with its old holder again. */
    public void acquire(String holder, Instant now) {
        try {
            mongo.upsert(lockQuery().addCriteria(new Criteria().orOperator(Criteria.where("expiresAt").lte(now),
                            Criteria.where("expiresAt").exists(false))),
                    new Update().set("holder", holder).set("expiresAt", now.plus(LEASE)).inc("sequence", 1), LOCKS);
        } catch (DuplicateKeyException held) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
    }

    public boolean tryFence(String holder, Instant now) {
        return mongo.updateFirst(lockQuery().addCriteria(Criteria.where("holder").is(holder).and("expiresAt").gt(now)),
                new Update().set("expiresAt", now.plus(LEASE)).inc("sequence", 1), LOCKS).getMatchedCount() == 1;
    }

    /** Fence both before and after every transaction's work, including completion and failure recording. */
    public void fence(String holder, Instant now) {
        if (!tryFence(holder, now)) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
    }

    public List<MigrationRun> interrupted(boolean production) {
        if (production && mongo.exists(tenantQuery().addCriteria(Criteria.where("env").is("PRODUCTION")
                .and("status").in("COMPLETED", "RECONCILED")), MigrationRun.class)) {
            throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED);
        }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("status").is("RUNNING")), MigrationRun.class);
    }

    /** Reset uses the same document lock as import batches and refuses a live import owner. */
    public void lockReset(Instant now) {
        mongo.upsert(lockQuery(), new Update().inc("sequence", 1), LOCKS);
        if (mongo.exists(lockQuery().addCriteria(Criteria.where("expiresAt").gt(now)), LOCKS)) {
            throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED);
        }
    }

    public void release(String holder) {
        mongo.updateFirst(lockQuery().addCriteria(Criteria.where("holder").is(holder)),
                new Update().unset("holder").unset("expiresAt"), LOCKS);
    }

    private Query lockQuery() { return tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())); }
}
