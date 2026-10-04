package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentOperationRepository extends TenantRepository<PaymentOperation> {
    public PaymentOperationRepository(MongoTemplate mongo) { super(mongo, PaymentOperation.class); }
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
        return mongo.find(tenantQuery().addCriteria(Criteria.where("kind").is("REFUND_LATE").and("resultId").is(null)), PaymentOperation.class);
    }
    public void completed(String id, String reference) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("resultId", reference), PaymentOperation.class);
    }
    /** Dispatcher inventory only; each command is subsequently re-read and executed inside its own tenant scope. */
    public List<PaymentOperation> pending() {
        return mongo.find(org.springframework.data.mongodb.core.query.Query.query(Criteria.where("resultId").is(null)).limit(100), PaymentOperation.class);
    }
}
