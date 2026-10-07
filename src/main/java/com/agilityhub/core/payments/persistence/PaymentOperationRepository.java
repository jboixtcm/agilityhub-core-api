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
                .addCriteria(PaymentRetryState.due(clock.instant())).with(org.springframework.data.domain.Sort.by("createdAt", "_id")).limit(100), PaymentOperation.class);
    }
    public void completed(String id, String reference) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("resultId", reference).set("processedAt", clock.instant()).set("outcome", "PROCESSED").inc("attempts", 1).unset("nextAttemptAt"), PaymentOperation.class);
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
    public boolean ready(String id) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("resultId").is(null).and("processedAt").is(null))
                .addCriteria(PaymentRetryState.due(clock.instant())), PaymentOperation.class);
    }
    public boolean failed(String id, java.time.Instant now, int max) {
        return PaymentRetryState.failed(mongo, tenantQuery().addCriteria(Criteria.where("_id").is(id).and("resultId").is(null)), "payment_operations", now, max);
    }
    /** Dispatcher inventory only; each command is subsequently re-read and executed inside its own tenant scope. */
    public List<PaymentOperation> pending() {
        return mongo.find(org.springframework.data.mongodb.core.query.Query.query(Criteria.where("resultId").is(null).and("processedAt").is(null))
                .addCriteria(PaymentRetryState.due(clock.instant())).with(org.springframework.data.domain.Sort.by("createdAt", "_id")).limit(100), PaymentOperation.class);
    }
}
