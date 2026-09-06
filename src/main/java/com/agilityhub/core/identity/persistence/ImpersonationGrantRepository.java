package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class ImpersonationGrantRepository extends TenantRepository<ImpersonationGrant> {
    public ImpersonationGrantRepository(MongoTemplate mongo) { super(mongo, ImpersonationGrant.class); }
    public boolean revoke(String id, Instant now) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("revokedAt").is(null)),
                new Update().set("revokedAt", now), ImpersonationGrant.class).getModifiedCount() == 1;
    }
    public void ensureIndexes() {
        mongo.indexOps(ImpersonationGrant.class).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
    }
}
