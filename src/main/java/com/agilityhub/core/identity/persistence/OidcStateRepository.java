package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Global identity capabilities; every lookup is by a hash and checked against its browser/client. */
@Repository
public class OidcStateRepository extends GlobalRepository<OidcState.Browser> {
    public OidcStateRepository(MongoTemplate mongo) { super(mongo, OidcState.Browser.class); }
    public void ensureIndexes() {
        for (Class<?> type : new Class<?>[]{OidcState.Browser.class, OidcState.Flow.class, OidcState.Code.class}) {
            mongo.indexOps(type).ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
        }
    }
    public void insert(Object value) { mongo.insert(value); }
    public OidcState.Flow flow(String hash, String browserHash, Instant now) {
        return mongo.findOne(live(hash, now).addCriteria(Criteria.where("browserHash").is(browserHash)), OidcState.Flow.class);
    }
    public boolean consumeFlow(String hash, String browserHash, Instant now) {
        return mongo.remove(live(hash, now).addCriteria(Criteria.where("browserHash").is(browserHash)), OidcState.Flow.class).getDeletedCount() == 1;
    }
    public OidcState.Code code(String hash, String clientId, Instant now) {
        return mongo.findOne(live(hash, now).addCriteria(Criteria.where("request.clientId").is(clientId)), OidcState.Code.class);
    }
    public boolean consumeCode(String hash, Instant now) { return mongo.remove(live(hash, now), OidcState.Code.class).getDeletedCount() == 1; }
    public void deleteBrowser(String hash) { mongo.remove(Query.query(Criteria.where("_id").is(hash)), OidcState.Browser.class); }
    private Query live(String hash, Instant now) { return Query.query(Criteria.where("_id").is(hash).and("expiresAt").gt(now)); }
}
