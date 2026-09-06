package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.application.TenantContext;
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
public class MagicLinkTokenRepository extends TenantRepository<MagicLinkToken> {
    public MagicLinkTokenRepository(MongoTemplate mongo) { super(mongo, MagicLinkToken.class); }
    private Query contextQuery() { return TenantContext.current() == null ? Query.query(Criteria.where("clubId").is(null)) : tenantQuery(); }
    @Override public MagicLinkToken insert(MagicLinkToken token) {
        if (TenantContext.current() == null && token.clubId() == null) { return mongo.insert(token); }
        return super.insert(token);
    }
    public Optional<MagicLinkToken> find(String hash, String clientId) {
        return Optional.ofNullable(mongo.findOne(contextQuery().addCriteria(Criteria.where("tokenHash").is(hash)
                .and("clientId").is(clientId)), MagicLinkToken.class));
    }
    public boolean consume(String id, Instant now) {
        return mongo.updateFirst(contextQuery().addCriteria(Criteria.where("_id").is(id).and("usedAt").is(null).and("expiresAt").gt(now)),
                new Update().set("usedAt", now), MagicLinkToken.class).getModifiedCount() == 1;
    }
    /** Global account invariant, serialized through the Account write in the same transaction. */
    public void trim(String accountId, MagicLinkToken.Purpose purpose, Instant now, String newestId) {
        var tokens = mongo.find(Query.query(Criteria.where("accountId").is(accountId).and("purpose").is(purpose)
                        .and("usedAt").is(null).and("expiresAt").gt(now)).with(Sort.by(Sort.Direction.DESC, "createdAt", "_id")), MagicLinkToken.class);
        if (tokens.size() > 3) {
            mongo.updateMulti(Query.query(Criteria.where("_id").in(tokens.stream().filter(token -> !token.id().equals(newestId)).skip(2).map(MagicLinkToken::id).toList())),
                    new Update().set("usedAt", now), MagicLinkToken.class);
        }
    }
    public void ensureIndexes() {
        mongo.indexOps(MagicLinkToken.class).ensureIndex(new Index().on("tokenHash", Sort.Direction.ASC).unique());
        mongo.indexOps(MagicLinkToken.class).ensureIndex(new Index().on("accountId", Sort.Direction.ASC).on("purpose", Sort.Direction.ASC));
        mongo.indexOps(MagicLinkToken.class).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
    }
}
