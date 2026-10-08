package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository extends TenantRepository<IdempotencyRecord> {
    /**
     * CONVENCIONS_API §7 (ruling E79, E5-T30 round 2): how long a claim `IN_PROGRESS` belongs to the request that took it. It is
     * longer than the longest request: a request's Mongo transaction lives 60 s at most (the server's transaction lifetime), the
     * provider's call of a checkout times out well within it, and a synchronous job trigger renews its own run lease every
     * 60 s (S15 R-15-06), so a retry that takes its claim over meets that lease, never a second run.
     */
    public static final Duration CLAIM_LEASE = Duration.ofMinutes(10);
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IdempotencyRepository.class);
    public IdempotencyRepository(MongoTemplate mongo) { super(mongo, IdempotencyRecord.class); }

    public void ensureIndexes() {
        mongo.indexOps(IdempotencyRecord.class).ensureIndex(new Index().on("clubId", Direction.ASC)
                .on("accountId", Direction.ASC).on("key", Direction.ASC).unique().named("scope_key"));
        mongo.indexOps(IdempotencyRecord.class).ensureIndex(new Index().on("createdAt", Direction.ASC)
                .expire(Duration.ofHours(24)).named("idempotency_retention"));
    }

    public Claim claim(String clubId, String accountId, String key, String hash, Instant now) {
        Query scope = scopeQuery(clubId).addCriteria(Criteria.where("accountId").is(accountId).and("key").is(key));
        // TTL cleanup is asynchronous, so expired keys must also be reusable before the next TTL sweep.
        mongo.remove(scopeQuery(clubId).addCriteria(Criteria.where("accountId").is(accountId).and("key").is(key)
                .and("createdAt").lte(now.minus(Duration.ofHours(24)))), IdempotencyRecord.class);
        // CONVENCIONS_API §7 (E79): a claim still IN_PROGRESS past its lease belongs to a request that stopped before its answer
        // or its clean-up (the process died). The same request (same key, scope and body) takes it over; another body never
        // does. The new record fences the old request out: its lock and its answer find no record and fail ({@link #taken}).
        long stale = mongo.remove(scopeQuery(clubId).addCriteria(Criteria.where("accountId").is(accountId).and("key").is(key)
                .and("status").is(IdempotencyRecord.Status.IN_PROGRESS).and("requestHash").is(hash)
                .and("createdAt").lte(now.minus(CLAIM_LEASE))), IdempotencyRecord.class).getDeletedCount();
        if (stale > 0) { LOG.warn("Idempotency claim taken over after its lease: clubId={} lease={}", clubId, CLAIM_LEASE); }
        var record = new IdempotencyRecord(UUID.randomUUID().toString(), clubId, accountId, key, hash,
                IdempotencyRecord.Status.IN_PROGRESS, 0, null, Map.of(), now);
        try {
            mongo.insert(record);
            return new Claim(record, true);
        } catch (DuplicateKeyException duplicate) {
            var existing = mongo.findOne(scope, IdempotencyRecord.class);
            if (existing == null) { throw duplicate; }
            return new Claim(existing, false);
        }
    }

    /** Throws {@link #taken} when the record is gone: a retry took the claim over after its lease (E5-T30 round 2). */
    public void lock(IdempotencyRecord record) {
        if (mongo.updateFirst(recordQuery(record), new Update().set("status", IdempotencyRecord.Status.IN_PROGRESS),
                IdempotencyRecord.class).getMatchedCount() == 0) { throw taken(); }
    }

    /** Throws {@link #taken} when the record is gone, so the caller's transaction rolls its effects back with the answer. */
    public void complete(IdempotencyRecord record, int status, byte[] body, Map<String, List<String>> headers) {
        if (mongo.updateFirst(recordQuery(record), new Update().set("status", IdempotencyRecord.Status.DONE)
                .set("responseStatus", status).set("responseBody", body).set("responseHeaders", headers),
                IdempotencyRecord.class).getMatchedCount() == 0) { throw taken(); }
    }

    /**
     * CONVENCIONS_API §7 (E79): the request no longer holds its key's claim; the retry that took it over is the one in progress
     * now, so this one answers what any other attempt gets meanwhile.
     */
    private static ApiException taken() {
        return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "IN_PROGRESS"));
    }

    /** Whether {@code record} still claims its key: false once a retry took the claim over after its lease (E79). */
    public boolean held(IdempotencyRecord record) { return mongo.exists(recordQuery(record), IdempotencyRecord.class); }

    public void abandon(IdempotencyRecord record) {
        mongo.remove(recordQuery(record).addCriteria(Criteria.where("status").is(IdempotencyRecord.Status.IN_PROGRESS)),
                IdempotencyRecord.class);
    }

    private Query recordQuery(IdempotencyRecord record) {
        return scopeQuery(record.clubId()).addCriteria(Criteria.where("_id").is(record.id())
                .and("accountId").is(record.accountId()));
    }

    /** Only an explicitly global request can use the null scope; a club request keeps TenantRepository's checks. */
    private Query scopeQuery(String clubId) {
        if (clubId != null) { return tenantQuery(clubId); }
        if (com.agilityhub.core.shared.application.TenantContext.current() != null) {
            throw new ApiException(ErrorCode.TENANT_MISMATCH);
        }
        return Query.query(Criteria.where("clubId").is(null));
    }

    public record Claim(IdempotencyRecord record, boolean acquired) { }
}
