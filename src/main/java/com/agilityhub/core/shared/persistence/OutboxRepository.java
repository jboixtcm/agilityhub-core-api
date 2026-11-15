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
public class OutboxRepository extends GlobalRepository<DomainEventRecord> {
    public static final Duration LEASE = Duration.ofMinutes(5);
    /** OUT-01: the longest wait between two attempts of a record (before OUT-01, 300 s). */
    public static final Duration MAX_BACKOFF = Duration.ofHours(1);
    static final String FORMER_TTL = "outbox_retention";

    public OutboxRepository(MongoTemplate mongo) { super(mongo, DomainEventRecord.class); }

    public void ensureIndexes() {
        var indexes = mongo.indexOps(DomainEventRecord.class);
        // R-15-19, INC-40 (E11-T10): P9 deletes a club's processed events after its `jobs.retention.domainEventsDays`; the former
        // 90-day TTL `outbox_retention` cut a longer retention short, so a deployed database loses it. The plain index serves P9.
        if (indexes.getIndexInfo().stream().anyMatch(index -> FORMER_TTL.equals(index.getName()))) {
            try { indexes.dropIndex(FORMER_TTL); }
            catch (org.springframework.dao.DataAccessException droppedByAnotherInstance) { /* gone either way */ }
        }
        indexes.createIndex(new Index().on("clubId", Direction.ASC).on("status", Direction.ASC).on("publishedAt", Direction.ASC)
                .named("outbox_cleanup"));
        indexes.createIndex(new Index().on("status", Direction.ASC).on("nextAttemptAt", Direction.ASC).named("outbox_dispatch"));
        indexes.createIndex(new Index().on("clubId", Direction.ASC).on("aggregateType", Direction.ASC)
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

    /**
     * OUT-01 (owner decision of 13 Nov 2026): the back-off after the n-th failed attempt doubles from 1 s up to {@link #MAX_BACKOFF}
     * (1, 2, 4 … 2,048 s, then every hour), so with `SharedConfiguration`'s {@code maxAttempts} the record keeps being retried for
     * about a day before it ends `FAILED`. True when this failure was the last one (the record is `FAILED` now).
     */
    public boolean failed(DomainEventRecord record, Instant now, int maxAttempts, Exception failure) {
        boolean last = record.attempts() >= maxAttempts;
        requireOwnedUpdate(record, new Update().set("status", last ? DomainEventRecord.Status.FAILED : DomainEventRecord.Status.PENDING)
                .set("nextAttemptAt", now.plus(backoff(record.attempts()))).unset("claimToken")
                // Exception messages may contain payloads or credentials; persist the type only.
                .set("error", failure.getClass().getSimpleName()));
        return last;
    }
    /** The wait after the {@code attempts}-th failed attempt: 2^(attempts − 1) seconds, at most {@link #MAX_BACKOFF}. */
    public static Duration backoff(int attempts) {
        return Duration.ofSeconds(Math.min(MAX_BACKOFF.toSeconds(), 1L << Math.min(30, Math.max(0, attempts - 1))));
    }

    public long count(DomainEventRecord.Status status) {
        return mongo.count(Query.query(Criteria.where("status").is(status)), DomainEventRecord.class);
    }

    private void requireOwnedUpdate(DomainEventRecord record, Update update) {
        Query owned = Query.query(Criteria.where("clubId").is(record.clubId())).addCriteria(Criteria.where("_id").is(record.id())
                .and("claimToken").is(record.claimToken()).and("status").is(DomainEventRecord.Status.PENDING));
        if (mongo.updateFirst(owned, update, DomainEventRecord.class).getMatchedCount() != 1) {
            throw new IllegalStateException("Outbox claim no longer owned");
        }
    }
}
