package com.agilityhub.core.platform.persistence.jobs;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** Distributed leases; never called inside a Mongo transaction (a DuplicateKey would abort it). */
@Repository
public class JobLockRepository extends GlobalRepository<JobLock> {
    public JobLockRepository(MongoTemplate mongo) { super(mongo, JobLock.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(JobLock.class).ensureIndex(new Index().on("expiresAt", ASC).expire(Duration.ZERO).named("job_lock_ttl"));
    }

    /** R-15-06: upsert guarded by `expiresAt < now`; a live lease makes the upsert hit the `_id` and fail with DuplicateKey. */
    public boolean acquire(String id, String holder, Instant now, Duration lease) {
        try {
            var query = Query.query(Criteria.where("_id").is(id).and("expiresAt").lt(now));
            var update = new Update().set("holder", holder).set("acquiredAt", now).set("expiresAt", now.plus(lease));
            return mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), JobLock.class) != null;
        } catch (DuplicateKeyException held) {
            return false;
        }
    }
    public boolean renew(String id, String holder, Instant now, Duration lease) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("holder").is(holder)),
                new Update().set("expiresAt", now.plus(lease)), JobLock.class).getModifiedCount() == 1;
    }
    public boolean held(String id, String holder, Instant now) {
        return mongo.exists(Query.query(Criteria.where("_id").is(id).and("holder").is(holder).and("expiresAt").gt(now)), JobLock.class);
    }
    public void release(String id, String holder) {
        mongo.remove(Query.query(Criteria.where("_id").is(id).and("holder").is(holder)), JobLock.class);
    }
}
