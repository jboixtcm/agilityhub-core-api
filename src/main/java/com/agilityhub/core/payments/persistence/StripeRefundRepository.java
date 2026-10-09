package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Internal refund delivery checkpoint; survives inbox cleanup and serializes different events for the same refund. */
@Repository
public class StripeRefundRepository extends TenantRepository<StripeRefundRepository.State> {
    @Document("stripe_refunds")
    public record State(@Id String id, String clubId, String intent, Money amount, String status, Instant at) implements TenantEntity { }
    /** Provider-side reservations, including refunds created outside this application's command queue. */
    public java.util.List<State> pending(String intent) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("intent").is(intent).and("status").is("pending")), State.class);
    }
    public String refundId(State state) { return state.id().substring(TenantContext.require().length() + 1); }
    public java.util.List<State> forIntent(String intent) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("intent").is(intent)), State.class);
    }
    public StripeRefundRepository(MongoTemplate mongo) { super(mongo, State.class); }
    private String id(String refundId) { return TenantContext.require() + ":" + refundId; }
    /** All rows and commands of one capture share this transaction fence, independently of the refund event id. */
    public void lockIntent(String intent) {
        if (intent == null) { return; }
        // Fence-only checkpoint: no intent/status/amount, so it cannot be counted as a provider refund.
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(id("intent:" + intent))), new Update().inc("sequence", 1), State.class);
    }
    public State lock(String refundId) {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(id(refundId))), new Update().inc("sequence", 1), State.class);
        return findById(id(refundId)).orElseThrow();
    }
    public void outcome(String refundId, String intent, Money amount, String status, Instant at) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id(refundId))),
                new Update().set("intent", intent).set("amount", amount).set("status", status).set("at", at), State.class);
    }
}
