package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Explicit account-wide operations for global revocation and the per-account device limit. */
@Repository
public class AccountSessionRepository extends GlobalRepository<RefreshToken> {
    public AccountSessionRepository(MongoTemplate mongo) { super(mongo, RefreshToken.class); }
    public List<RefreshToken> active(String accountId, long version, Instant now) {
        return mongo.find(Query.query(RefreshTokenRepository.active(accountId, now).and("tokenFamilyVersion").is(version))
                .with(Sort.by(Sort.Direction.ASC, "createdAt", "_id")), RefreshToken.class);
    }
    public void revokeFamily(String accountId, String familyId, Instant now) {
        mongo.updateMulti(Query.query(Criteria.where("accountId").is(accountId).and("familyId").is(familyId)),
                RefreshTokenRepository.revoked(now), RefreshToken.class);
    }
    public void revokeAll(String accountId, Instant now) {
        mongo.updateMulti(Query.query(Criteria.where("accountId").is(accountId)), RefreshTokenRepository.revoked(now), RefreshToken.class);
    }
    public void preserveFamily(String accountId, String familyId, long version) {
        mongo.updateMulti(Query.query(Criteria.where("accountId").is(accountId).and("familyId").is(familyId)
                        .and("revokedAt").is(null)), new Update().set("tokenFamilyVersion", version), RefreshToken.class);
    }
    public void revokeOthers(String accountId, String familyId, Instant now) {
        mongo.updateMulti(Query.query(Criteria.where("accountId").is(accountId).and("familyId").ne(familyId)),
                RefreshTokenRepository.revoked(now), RefreshToken.class);
    }
}
