package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** `push_subscriptions` with the S11 §3 indexes: unique `{clubId, endpointHash}` and the sender's `{clubId, accountId, status}`. */
@Repository
public class PushSubscriptionRepository extends TenantRepository<PushSubscription> {
    public PushSubscriptionRepository(MongoTemplate mongo) { super(mongo, PushSubscription.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(PushSubscription.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("endpointHash", ASC).unique().named("push_club_endpoint"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("accountId", ASC).on("status", ASC).named("push_club_account_status"));
    }
    /** A subscription of the tenant owned by the account (`DELETE /push-subscriptions/{id}`, «pròpia»): another account's is absent. */
    public Optional<PushSubscription> findOwn(String id, String accountId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("accountId").is(accountId)), PushSubscription.class));
    }
}
