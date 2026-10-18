package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository extends TenantRepository<RefreshToken> {
    public RefreshTokenRepository(MongoTemplate mongo) { super(mongo, RefreshToken.class); }
    // Only global identity grants have null clubId. Ordinary club operations retain TenantRepository guards.
    private Query contextQuery() { return TenantContext.current() == null ? Query.query(Criteria.where("clubId").is(null)) : tenantQuery(); }
    @Override public RefreshToken insert(RefreshToken token) {
        if (TenantContext.current() == null && token.clubId() == null) { return mongo.insert(token); }
        return super.insert(token);
    }
    public Optional<RefreshToken> find(String hash, String clientId) {
        return Optional.ofNullable(mongo.findOne(contextQuery().addCriteria(Criteria.where("tokenHash").is(hash)
                .and("clientId").is(clientId)), RefreshToken.class));
    }
    public boolean rotate(String id, String nextHash, Instant now) {
        return mongo.updateFirst(contextQuery().addCriteria(Criteria.where("_id").is(id)
                        .and("replacedByHash").is(null).and("revokedAt").is(null).and("expiresAt").gt(now)),
                new Update().set("replacedByHash", nextHash).set("lastUsedAt", now).set("status", RefreshToken.Status.ROTATED), RefreshToken.class).getModifiedCount() == 1;
    }
    /**
     * INC-36 (S01 R-01-06): takes the one extra use a rotated token gets inside `auth.refreshReuseGraceSeconds`. `graceUsedAt` is
     * set once, so a third presentation of the token is a reuse again.
     */
    public boolean useGrace(String id, Instant now) {
        return mongo.updateFirst(contextQuery().addCriteria(Criteria.where("_id").is(id).and("replacedByHash").ne(null)
                .and("revokedAt").is(null).and("graceUsedAt").is(null)), new Update().set("graceUsedAt", now), RefreshToken.class).getModifiedCount() == 1;
    }
    /** INC-36 (S01 R-01-06): the live child a rotated token was replaced by, if nobody has rotated or revoked it since. */
    public Optional<RefreshToken> liveChild(RefreshToken rotated, Instant now) {
        return Optional.ofNullable(mongo.findOne(contextQuery().addCriteria(active(rotated.accountId(), now)
                .and("familyId").is(rotated.familyId()).and("tokenHash").is(rotated.replacedByHash())), RefreshToken.class));
    }
    /**
     * INC-36 (S01 R-01-06): before a grace issues its sibling, the family keeps only the presented token's live child; any other
     * live token (the leftover of an earlier race, which a cookie jar no longer holds) is retired as rotated, by the sibling, with
     * its grace already spent, so presenting it later is a reuse that revokes the family. A family never holds more than two
     * live tokens.
     */
    public void retireOtherLiveTokens(String familyId, String keepId, String siblingHash, Instant now) {
        mongo.updateMulti(contextQuery().addCriteria(Criteria.where("familyId").is(familyId).and("_id").ne(keepId)
                        .and("replacedByHash").is(null).and("revokedAt").is(null)),
                new Update().set("replacedByHash", siblingHash).set("graceUsedAt", now).set("status", RefreshToken.Status.ROTATED), RefreshToken.class);
    }
    public void revokeFamily(String familyId, Instant now) {
        mongo.updateMulti(contextQuery().addCriteria(Criteria.where("familyId").is(familyId)), revoked(now), RefreshToken.class);
    }
    public Optional<RefreshToken> activeFamily(String accountId, String familyId, Instant now) {
        return Optional.ofNullable(mongo.findOne(contextQuery().addCriteria(active(accountId, now).and("familyId").is(familyId)), RefreshToken.class));
    }
    public Optional<RefreshToken> ownedToken(String accountId, String hash) {
        return Optional.ofNullable(mongo.findOne(contextQuery().addCriteria(Criteria.where("accountId").is(accountId)
                .and("tokenHash").is(hash)), RefreshToken.class));
    }
    public List<RefreshToken> sessions(String accountId, Instant now, long version) {
        return mongo.find(contextQuery().addCriteria(active(accountId, now).and("tokenFamilyVersion").is(version))
                .with(Sort.by(Direction.DESC, "createdAt")), RefreshToken.class);
    }
    public void profile(String accountId, String familyId, Role profile) {
        mongo.updateMulti(contextQuery().addCriteria(Criteria.where("accountId").is(accountId).and("familyId").is(familyId)),
                new Update().set("activeProfile", profile), RefreshToken.class);
    }
    static Criteria active(String accountId, Instant now) {
        return Criteria.where("accountId").is(accountId).and("revokedAt").is(null)
                .and("replacedByHash").is(null).and("expiresAt").gt(now);
    }
    static Update revoked(Instant now) { return new Update().set("revokedAt", now).set("status", RefreshToken.Status.REVOKED); }
    public void ensureIndexes() {
        mongo.indexOps(RefreshToken.class).createIndex(new Index().on("tokenHash", Direction.ASC).unique().named("refresh_hash"));
        mongo.indexOps(RefreshToken.class).createIndex(new Index().on("clubId", Direction.ASC).on("familyId", Direction.ASC));
        mongo.indexOps(RefreshToken.class).createIndex(new Index().on("accountId", Direction.ASC).on("createdAt", Direction.ASC));
        // Retain rotated tokens until the family expires so delayed reuse still revokes the active descendant.
        mongo.indexOps(RefreshToken.class).createIndex(new Index().on("expiresAt", Direction.ASC).expire(0));
    }
    public void extendFamilyRetention(String familyId, Instant expiry) {
        mongo.updateMulti(contextQuery().addCriteria(Criteria.where("familyId").is(familyId)),
                new Update().max("expiresAt", expiry), RefreshToken.class);
    }
}
