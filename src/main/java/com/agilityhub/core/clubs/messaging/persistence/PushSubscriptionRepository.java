package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/**
 * `push_subscriptions` with the S11 §3 indexes: the endpoint is unique per club among the `ACTIVE` subscriptions (partial
 * `{clubId, endpointHash}`) and once per account (`{clubId, endpointHash, accountId}`), and the sender's `{clubId, accountId,
 * status}`. A subscription never changes owner (E76): the browser of another account ends the previous owner's and gets its own
 * row, so the old unique `{clubId, endpointHash}` of E7-T01 is dropped.
 */
@Repository
public class PushSubscriptionRepository extends TenantRepository<PushSubscription> {
    static final String LEGACY_ENDPOINT_INDEX = "push_club_endpoint";
    public PushSubscriptionRepository(MongoTemplate mongo) { super(mongo, PushSubscription.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(PushSubscription.class);
        if (indexes.getIndexInfo().stream().anyMatch(index -> LEGACY_ENDPOINT_INDEX.equals(index.getName()))) { indexes.dropIndex(LEGACY_ENDPOINT_INDEX); }
        indexes.ensureIndex(new Index().on("clubId", ASC).on("endpointHash", ASC).unique().named("push_club_endpoint_active")
                .partial(org.springframework.data.mongodb.core.index.PartialIndexFilter.of(new org.bson.Document("status", PushSubscription.Status.ACTIVE.name()))));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("endpointHash", ASC).on("accountId", ASC).unique().named("push_club_endpoint_account"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("accountId", ASC).on("status", ASC).named("push_club_account_status"));
    }
    /** The `ACTIVE` subscription ids of each account of the tenant (R-11-03 PUSH: one delivery per subscription). */
    public java.util.Map<String, java.util.List<String>> activeFor(java.util.Collection<String> accountIds) {
        var found = new java.util.LinkedHashMap<String, java.util.List<String>>();
        if (accountIds.isEmpty()) { return found; }
        mongo.find(tenantQuery().addCriteria(Criteria.where("accountId").in(accountIds).and("status").is(PushSubscription.Status.ACTIVE)), PushSubscription.class)
                .forEach(s -> found.computeIfAbsent(s.accountId(), key -> new java.util.ArrayList<>()).add(s.id()));
        return found;
    }
    /** A push the service accepted: `lastSuccessAt`, and the consecutive-failure count back to 0. */
    public void pushed(String id, java.time.Instant at) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().set("lastSuccessAt", at).set("failureCount", 0), PushSubscription.class);
    }
    /** One more consecutive non-retryable failure; returns the new count. */
    public int failed(String id) {
        var updated = mongo.findAndModify(tenantQuery().addCriteria(Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().inc("failureCount", 1),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), PushSubscription.class);
        return updated == null ? 0 : updated.failureCount();
    }
    /** `ACTIVE` → `EXPIRED` (404/410, 3 failures, logout); `false` when it was not active any more. */
    public boolean expire(String id, java.time.Instant at) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is(PushSubscription.Status.ACTIVE)),
                new org.springframework.data.mongodb.core.query.Update().set("status", PushSubscription.Status.EXPIRED).set("expiredAt", at).inc("version", 1),
                PushSubscription.class).getModifiedCount() == 1;
    }
    /** The tenant's subscriptions of an endpoint, one per account that ever subscribed it (R-11-07: upsert by endpoint). */
    public java.util.List<PushSubscription> byEndpointHash(String endpointHash) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("endpointHash").is(endpointHash)), PushSubscription.class);
    }
    /**
     * `POST /push-subscriptions` on an endpoint the same account holds: the new keys and device, `ACTIVE` again (a browser that
     * subscribes again after an expiry or a logout). The owner never changes; `false` when the stored version moved meanwhile.
     */
    public boolean resubscribe(PushSubscription current, PushSubscription.Keys keys, String deviceLabel, String userAgent, java.time.Instant at, String by) {
        var update = new org.springframework.data.mongodb.core.query.Update().set("keys", keys).set("deviceLabel", deviceLabel)
                .set("userAgent", userAgent).set("status", PushSubscription.Status.ACTIVE).set("failureCount", 0).unset("expiredAt")
                .set("updatedAt", at).set("updatedBy", by).inc("version", 1);
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(current.id()).and("accountId").is(current.accountId()).and("version").is(current.version()));
        return mongo.updateFirst(query, update, PushSubscription.class).getModifiedCount() == 1;
    }
    /** A subscription of the tenant owned by the account (`DELETE /push-subscriptions/{id}`, «pròpia»): another account's is absent. */
    public Optional<PushSubscription> findOwn(String id, String accountId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("accountId").is(accountId)), PushSubscription.class));
    }
}
