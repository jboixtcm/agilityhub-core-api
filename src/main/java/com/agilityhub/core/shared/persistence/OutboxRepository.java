package com.agilityhub.core.shared.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository extends TenantRepository<DomainEventRecord> {
    public static final Duration LEASE = Duration.ofMinutes(5);

    public OutboxRepository(MongoTemplate mongo) { super(mongo); }

    public void ensureIndexes() {
        var indexes = mongo.indexOps(DomainEventRecord.class);
        indexes.ensureIndex(new Index().on("publishedAt", Direction.ASC).expire(Duration.ofDays(90)).named("outbox_retention"));
        indexes.ensureIndex(new Index().on("status", Direction.ASC).on("nextAttemptAt", Direction.ASC).named("outbox_dispatch"));
        indexes.ensureIndex(new Index().on("clubId", Direction.ASC).on("aggregateType", Direction.ASC)
                .on("aggregateId", Direction.ASC).named("outbox_aggregate"));
    }

    // System dispatcher intentionally claims across tenants; every subsequent write uses the record's tenant.
    public DomainEventRecord claim(Instant now) {
        Query ready = Query.query(Criteria.where("status").is(DomainEventRecord.Status.PENDING)
                .and("nextAttemptAt").lte(now)).with(Sort.by("nextAttemptAt", "_id"));
        try {
            return mongo.findAndModify(ready, new Update().set("nextAttemptAt", now.plus(LEASE))
                    .set("claimToken", UUID.randomUUID().toString()).inc("attempts", 1),
                    FindAndModifyOptions.options().returnNew(true), DomainEventRecord.class);
        } catch (TransientDataAccessException competingTransaction) {
            return null; // Another dispatcher owns the write lock; try again on the next tick.
        }
    }

    /** Called inside the handler transaction: the write fences expired leases until the handler commits. */
    public void lock(DomainEventRecord record, Instant now) {
        // Force a write even when the injected clock has not advanced since claim().
        requireOwnedUpdate(record, new Update().inc("lockVersion", 1).set("nextAttemptAt", now.plus(LEASE)));
    }

    public void processed(DomainEventRecord record, String consumer, Instant now) {
        requireOwnedUpdate(record, new Update().set("processedAt." + consumer, now)
                .set("nextAttemptAt", now.plus(LEASE)));
    }

    public void published(DomainEventRecord record, Instant now) {
        requireOwnedUpdate(record, new Update().set("status", DomainEventRecord.Status.PUBLISHED)
                .set("publishedAt", now).unset("error").unset("claimToken"));
    }

    public void failed(DomainEventRecord record, Instant now, int maxAttempts, Exception failure) {
        long seconds = Math.min(300, 1L << Math.min(9, record.attempts() - 1));
        requireOwnedUpdate(record, new Update().set("status", record.attempts() >= maxAttempts
                ? DomainEventRecord.Status.FAILED : DomainEventRecord.Status.PENDING)
                .set("nextAttemptAt", now.plusSeconds(seconds)).unset("claimToken")
                // Exception messages may contain payloads or credentials; persist the type only.
                .set("error", failure.getClass().getSimpleName()));
    }

    public long count(DomainEventRecord.Status status) {
        return mongo.count(Query.query(Criteria.where("status").is(status)), DomainEventRecord.class);
    }

    private void requireOwnedUpdate(DomainEventRecord record, Update update) {
        Query owned = tenantQuery(record.clubId()).addCriteria(Criteria.where("_id").is(record.id())
                .and("claimToken").is(record.claimToken()).and("status").is(DomainEventRecord.Status.PENDING));
        if (mongo.updateFirst(owned, update, DomainEventRecord.class).getMatchedCount() != 1) {
            throw new IllegalStateException("Outbox claim no longer owned");
        }
    }
}
