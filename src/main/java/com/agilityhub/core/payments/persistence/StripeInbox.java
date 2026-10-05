package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Tenant-scoped processing state. Only the allow-listed provider references needed for recovery are retained, never the raw payload. */
@Repository
public class StripeInbox extends TenantRepository<StripeEvent> {
    private final java.time.Clock clock;
    public StripeInbox(MongoTemplate mongo, java.time.Clock clock) { super(mongo, StripeEvent.class); this.clock = clock; }
    public void receive(StripeEvent event, Document work) {
        tenantQuery(event.clubId());
        var row = new Document(); mongo.getConverter().write(event, row); row.put("work", work);
        mongo.insert(row, "stripe_events");
    }
    public void work(String id, Document work) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("work", work), StripeEvent.class);
    }
    public Document work(String id) {
        var row = mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id)), Document.class, "stripe_events");
        return row == null ? null : row.get("work", Document.class);
    }
    public void outcome(String id, String outcome, Instant at) {
        var update = new Update().set("outcome", outcome).set("processedAt", at).inc("attempts", 1).unset("nextAttemptAt").unset("work");
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), update, StripeEvent.class);
    }
    public void reason(String id, String reason) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("reason", reason), StripeEvent.class);
    }
    public boolean failed(String id, Instant now, int max) {
        return PaymentRetryState.failed(mongo, tenantQuery().addCriteria(Criteria.where("_id").is(id)), "stripe_events", now, max);
    }
    public void lock(String id) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().inc("processingSequence", 1), StripeEvent.class);
    }
    /** Dispatcher inventory only; processing always opens the stored club and re-reads through TenantRepository. */
    public List<StripeEvent> pending() { return mongo.find(Query.query(Criteria.where("processedAt").is(null)).addCriteria(PaymentRetryState.due(clock.instant()))
            .with(org.springframework.data.domain.Sort.by("receivedAt", "_id")).limit(100), StripeEvent.class); }
}
