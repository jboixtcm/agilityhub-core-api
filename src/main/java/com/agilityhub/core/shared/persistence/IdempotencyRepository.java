package com.agilityhub.core.shared.persistence;

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
    public IdempotencyRepository(MongoTemplate mongo) { super(mongo); }

    public void ensureIndexes() {
        mongo.indexOps(IdempotencyRecord.class).ensureIndex(new Index().on("clubId", Direction.ASC)
                .on("accountId", Direction.ASC).on("key", Direction.ASC).unique().named("scope_key"));
        mongo.indexOps(IdempotencyRecord.class).ensureIndex(new Index().on("createdAt", Direction.ASC)
                .expire(Duration.ofHours(24)).named("idempotency_retention"));
    }

    public Claim claim(String clubId, String accountId, String key, String hash, Instant now) {
        Query scope = tenantQuery(clubId).addCriteria(Criteria.where("accountId").is(accountId).and("key").is(key));
        // TTL cleanup is asynchronous, so expired keys must also be reusable before the next TTL sweep.
        mongo.remove(tenantQuery(clubId).addCriteria(Criteria.where("accountId").is(accountId).and("key").is(key)
                .and("createdAt").lte(now.minus(Duration.ofHours(24)))), IdempotencyRecord.class);
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

    public void lock(IdempotencyRecord record) {
        mongo.updateFirst(recordQuery(record), new Update().set("status", IdempotencyRecord.Status.IN_PROGRESS),
                IdempotencyRecord.class);
    }

    public void complete(IdempotencyRecord record, int status, byte[] body, Map<String, List<String>> headers) {
        mongo.updateFirst(recordQuery(record), new Update().set("status", IdempotencyRecord.Status.DONE)
                .set("responseStatus", status).set("responseBody", body).set("responseHeaders", headers),
                IdempotencyRecord.class);
    }

    public void abandon(IdempotencyRecord record) {
        mongo.remove(recordQuery(record).addCriteria(Criteria.where("status").is(IdempotencyRecord.Status.IN_PROGRESS)),
                IdempotencyRecord.class);
    }

    private Query recordQuery(IdempotencyRecord record) {
        return tenantQuery(record.clubId()).addCriteria(Criteria.where("_id").is(record.id())
                .and("accountId").is(record.accountId()));
    }

    public record Claim(IdempotencyRecord record, boolean acquired) { }
}
