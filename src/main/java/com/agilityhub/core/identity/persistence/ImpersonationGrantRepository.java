package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class ImpersonationGrantRepository extends TenantRepository<ImpersonationGrant> {
    /**
     * INC-37: an ended or expired grant stays a day, so the expiry sweep ({@link #expiredOpen}) finds every grant that expired
     * while no instance ran it (it ran within a minute before, when Mongo's TTL monitor could delete a grant first).
     */
    static final Duration RETENTION = Duration.ofDays(1);
    static final String RETENTION_INDEX = "impersonation_grant_retention";
    public ImpersonationGrantRepository(MongoTemplate mongo) { super(mongo, ImpersonationGrant.class); }
    /** «Surt» (`POST /oauth2/revoke`): the grant ends once, with `LOGOUT`. */
    public boolean revoke(String id, Instant now) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("revokedAt").is(null)),
                new Update().set("revokedAt", now).set("endReason", ImpersonationGrant.EndReason.LOGOUT), ImpersonationGrant.class).getModifiedCount() == 1;
    }
    /** INC-37: an impersonation past its `expiresAt` and not ended yet ends once, with `EXPIRED`; false when a revoke came first. */
    public boolean expire(String id, Instant now) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("revokedAt").is(null).and("expiresAt").lte(now)),
                new Update().set("revokedAt", now).set("endReason", ImpersonationGrant.EndReason.EXPIRED), ImpersonationGrant.class).getModifiedCount() == 1;
    }
    /**
     * INC-37, across clubs (the sweep opens each grant's club before it ends it): the impersonations (never a support access)
     * past their `expiresAt` that nothing ended yet, oldest first (`expiresAt`, then `_id`), at most {@code limit}. E11-T09 round
     * 2: only those after {@code after} (null: from the first), so a sweep pages past the grants it could not end.
     */
    public List<ImpersonationGrant> expiredOpen(Instant now, ImpersonationGrant after, int limit) {
        var query = Query.query(Criteria.where("revokedAt").is(null).and("expiresAt").lte(now).and("type").ne(ImpersonationGrant.Type.SUPPORT));
        if (after != null) {
            query.addCriteria(new Criteria().orOperator(Criteria.where("expiresAt").gt(after.expiresAt()),
                    Criteria.where("expiresAt").is(after.expiresAt()).and("_id").gt(after.id())));
        }
        return mongo.find(query.with(Sort.by("expiresAt", "_id")).limit(limit), ImpersonationGrant.class);
    }
    /**
     * INC-37 (S01 R-01-16), across clubs: whether a grant whose JWT can still be valid at {@code now} was signed before
     * {@code rotatedAt} (or before `createdAt` was stored), so by a key that a rotation now would retire.
     */
    public boolean liveSignedBefore(Instant rotatedAt, Instant now) {
        var signedBefore = new Criteria().orOperator(Criteria.where("createdAt").is(null), Criteria.where("createdAt").lt(rotatedAt));
        return mongo.exists(Query.query(Criteria.where("revokedAt").is(null).and("expiresAt").gt(now)).addCriteria(signedBefore), ImpersonationGrant.class);
    }
    public void ensureIndexes() {
        var indexes = mongo.indexOps(ImpersonationGrant.class);
        var existing = indexes.getIndexInfo().stream().filter(index -> index.getIndexFields().size() == 1
                && index.getIndexFields().getFirst().getKey().equals("expiresAt")).findFirst();
        if (existing.isEmpty()) {
            indexes.createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(RETENTION).named(RETENTION_INDEX));
        } else if (!existing.get().getExpireAfter().equals(java.util.Optional.of(RETENTION))) {
            indexes.alterIndex(existing.get().getName(), IndexOptions.expireAfter(RETENTION));
        }
    }
}
