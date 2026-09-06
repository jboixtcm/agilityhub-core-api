package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class HandoffCodeRepository extends TenantRepository<HandoffCode> {
    public HandoffCodeRepository(MongoTemplate mongo) { super(mongo, HandoffCode.class); }
    /** A high-entropy capability resolves the tenant at the global OAuth host; no account/id lookup is exposed. */
    public Optional<HandoffCode> resolve(String hash, String targetClientId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("codeHash").is(hash)
                .and("targetClientId").is(targetClientId)), HandoffCode.class));
    }
    public boolean consume(String id, Instant now) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("usedAt").is(null).and("expiresAt").gt(now)),
                new Update().set("usedAt", now), HandoffCode.class).getModifiedCount() == 1;
    }
    public void ensureIndexes() {
        mongo.indexOps(HandoffCode.class).ensureIndex(new Index().on("codeHash", Sort.Direction.ASC).unique());
        mongo.indexOps(HandoffCode.class).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
    }
}
