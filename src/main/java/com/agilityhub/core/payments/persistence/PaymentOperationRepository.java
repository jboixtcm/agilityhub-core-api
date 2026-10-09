package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentOperationRepository extends TenantRepository<PaymentOperation> {
    private final java.time.Clock clock;
    public PaymentOperationRepository(MongoTemplate mongo, java.time.Clock clock) { super(mongo, PaymentOperation.class); this.clock = clock; }
    @Override public PaymentOperation insert(PaymentOperation operation) {
        var inserted = super.insert(operation);
        submissionUncertain(operation.id(), false);
        return inserted;
    }
    @jakarta.annotation.PostConstruct public void indexes() {
        mongo.indexOps(PaymentOperation.class).ensureIndex(new org.springframework.data.mongodb.core.index.Index()
                .on("clubId", org.springframework.data.domain.Sort.Direction.ASC).on("key", org.springframework.data.domain.Sort.Direction.ASC).unique());
    }
    public java.util.Optional<PaymentOperation> byKey(String key) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("key").is(key)), PaymentOperation.class));
    }
    public List<PaymentOperation> forTarget(String target) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("targetId").is(target)), PaymentOperation.class);
    }
    public void requestReference(String id, String reference) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("requestReference", reference), PaymentOperation.class);
    }
    public java.util.Optional<PaymentOperation> forRequest(String reference) {
        if (reference == null) { return java.util.Optional.empty(); }
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("requestReference").is(reference)), PaymentOperation.class));
    }
    public java.util.Optional<PaymentOperation> forResult(String reference) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("resultId").is(reference)), PaymentOperation.class));
    }
    public List<PaymentOperation> lateRefunds() {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("kind").is("REFUND_LATE").and("resultId").is(null).and("processedAt").is(null))
                .addCriteria(new Criteria().andOperator(PaymentRetryState.due(clock.instant()), replayable(clock.instant())))
                .with(org.springframework.data.domain.Sort.by("createdAt", "_id")).limit(100), PaymentOperation.class);
    }
    public void completed(String id, String reference) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("resultId", reference).set("processedAt", clock.instant()).set("outcome", "PROCESSED").inc("attempts", 1).unset("nextAttemptAt"), PaymentOperation.class);
    }
    /** A validated webhook closes a lost-response command in the same transaction as its ledger settlement. */
    public void reconciled(String id, String reference) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("resultId").is(null)),
                new Update().set("resultId", reference).set("processedAt", clock.instant()).set("outcome", "PROCESSED")
                        .set("submissionUncertain", false).unset("nextAttemptAt"), PaymentOperation.class);
    }
    public boolean refundSettled(String id, UpfrontPayment.Refund refund) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("refund").is(null)),
                new Update().set("refund", refund), PaymentOperation.class).getModifiedCount() == 1;
    }
    public void providerStatus(String id, String status) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("providerStatus", status), PaymentOperation.class);
    }
    public void refundStatus(String id, String status) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("refundStatus", status), PaymentOperation.class);
    }
    public boolean refundFailed(String id) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(id)).addCriteria(new Criteria().orOperator(
                Criteria.where("refundStatus").in("failed", "canceled"), Criteria.where("refundStatus").is(null).and("providerStatus").in("failed", "canceled"))), PaymentOperation.class);
    }
    public boolean reverseRefund(String id, String refundId) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("refund.providerRef").is(refundId)),
                new Update().unset("refund"), PaymentOperation.class).getModifiedCount() == 1;
    }
    /** A disabled provider postpones an unclaimed command without spending its attempts; the delay keeps the pending set fair. */
    public void defer(String id, java.time.Instant until) {
        var now = clock.instant();
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("resultId").is(null).and("processedAt").is(null))
                .addCriteria(new Criteria().orOperator(Criteria.where("claimUntil").is(null), Criteria.where("claimUntil").lte(now))),
                new Update().set("nextAttemptAt", until), PaymentOperation.class);
    }
    /** E8-T11: the claimant, already fenced, postpones its own command after an outage, without spending an attempt. */
    public void postpone(String id, java.time.Instant until) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("resultId").is(null).and("processedAt").is(null)),
                new Update().set("nextAttemptAt", until), PaymentOperation.class);
    }
    public boolean submissionUncertain(String id) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("submissionUncertain").ne(false)), PaymentOperation.class);
    }
    public void submissionUncertain(String id, boolean uncertain) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("submissionUncertain", uncertain), PaymentOperation.class);
    }
    /** Far longer than the provider's 30-second call budget; a crashed worker leaves uncertainty behind. */
    public static final java.time.Duration CLAIM_LEASE = java.time.Duration.ofMinutes(10);
    // Stripe retains keys for at least 24h. Reserve the full 30-second provider-call budget before that boundary.
    private static final java.time.Duration REPLAY_WINDOW = java.time.Duration.ofHours(24).minusSeconds(30);
    private static Criteria replayable(java.time.Instant now) {
        return new Criteria().orOperator(
                new Criteria().norOperator(Criteria.where("kind").is("CHARGE"), Criteria.where("kind").regex("^REFUND")),
                Criteria.where("submissionUncertain").is(false),
                Criteria.where("firstSubmittedAt").gt(now.minus(REPLAY_WINDOW)).lte(now));
    }
    public record Claim(String token, boolean previouslyUncertain) { }
    public Claim claim(String id, boolean money) {
        var now = clock.instant();
        String token = java.util.UUID.randomUUID().toString();
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("resultId").is(null).and("processedAt").is(null))
                .addCriteria(new Criteria().andOperator(PaymentRetryState.due(now), replayable(now), new Criteria().orOperator(
                        Criteria.where("claimUntil").is(null), Criteria.where("claimUntil").lte(now))));
        var update = new Update().set("claimToken", token).set("claimUntil", now.plus(CLAIM_LEASE));
        if (money) { update.set("submissionUncertain", true); }
        var previous = mongo.findAndModify(query, update, org.bson.Document.class, "payment_operations");
        if (previous == null) { return null; }
        boolean uncertain = money && !Boolean.FALSE.equals(previous.getBoolean("submissionUncertain"));
        if (money && !uncertain) {
            // The caller's transaction commits this timestamp and the claim before any provider submission.
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("claimToken").is(token)),
                    new Update().set("firstSubmittedAt", now), PaymentOperation.class);
        }
        return new Claim(token, uncertain);
    }
    /** A write fences the entire surrounding settlement/failure transaction against a newer claimant. */
    public boolean fence(String id, String token) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("claimToken").is(token)
                        .and("claimUntil").gt(clock.instant())), new Update().inc("claimWrites", 1L), PaymentOperation.class).getMatchedCount() == 1;
    }
    public void release(String id, String token) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("claimToken").is(token)),
                new Update().unset("claimToken").unset("claimUntil"), PaymentOperation.class);
    }
    public boolean failed(String id, java.time.Instant now, int max) {
        return PaymentRetryState.failed(mongo, tenantQuery().addCriteria(Criteria.where("_id").is(id).and("resultId").is(null)), "payment_operations", now, max);
    }
    /** Dispatcher inventory only; each command is subsequently re-read and executed inside its own tenant scope. */
    public List<PaymentOperation> pending() {
        return mongo.find(org.springframework.data.mongodb.core.query.Query.query(Criteria.where("resultId").is(null).and("processedAt").is(null))
                .addCriteria(new Criteria().andOperator(PaymentRetryState.due(clock.instant()), replayable(clock.instant())))
                .with(org.springframework.data.domain.Sort.by("createdAt", "_id")).limit(100), PaymentOperation.class);
    }
}
