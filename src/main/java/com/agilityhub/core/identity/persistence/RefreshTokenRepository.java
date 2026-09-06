package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository extends TenantRepository<RefreshToken> {
    public RefreshTokenRepository(MongoTemplate mongo) { super(mongo, RefreshToken.class); }
    public Optional<RefreshToken> find(String hash, String clientId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("tokenHash").is(hash)
                .and("clientId").is(clientId)), RefreshToken.class));
    }
    public boolean rotate(String id, String nextHash, Instant now) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)
                        .and("replacedByHash").is(null).and("revokedAt").is(null).and("expiresAt").gt(now)),
                new Update().set("replacedByHash", nextHash).set("lastUsedAt", now), RefreshToken.class).getModifiedCount() == 1;
    }
    public void revokeFamily(String familyId, Instant now) {
        mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("familyId").is(familyId)),
                new Update().set("revokedAt", now), RefreshToken.class);
    }
    public void ensureIndexes() {
        mongo.indexOps(RefreshToken.class).ensureIndex(new Index().on("tokenHash", Direction.ASC).unique().named("refresh_hash"));
        mongo.indexOps(RefreshToken.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("familyId", Direction.ASC));
        mongo.indexOps(RefreshToken.class).ensureIndex(new Index().on("expiresAt", Direction.ASC).expire(0));
    }
}
