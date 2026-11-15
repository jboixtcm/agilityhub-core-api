package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
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
    /**
     * PAY-02 (owner decision of 13 Nov 2026): a failed attempt of an unprocessed event. It spends one attempt and the event is
     * FAILED; after the n-th attempt it waits {@code backoff[n-1]}, and once {@code backoff.size() + 1} attempts are spent it
     * stops (processedAt) and is kept, its recovery data included, until the retention cleanup. True when this attempt was
     * the last one.
     */
    public boolean failed(String id, Instant now, List<java.time.Duration> backoff) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("processedAt").is(null));
        var row = mongo.findAndModify(query, new Update().inc("attempts", 1).set("outcome", "FAILED"),
                FindAndModifyOptions.options().returnNew(true), Document.class, "stripe_events");
        if (row == null) { return false; }
        int attempts = ((Number) row.get("attempts")).intValue();
        boolean exhausted = attempts > backoff.size();
        mongo.updateFirst(query, exhausted ? new Update().set("processedAt", now).unset("nextAttemptAt")
                : new Update().set("nextAttemptAt", now.plus(backoff.get(attempts - 1))), "stripe_events");
        return exhausted;
    }
    /** PAY-02: an attempt an outage of the provider stopped spends none; the event is FAILED and runs again at {@code until}. */
    public void postpone(String id, Instant until) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("processedAt").is(null)),
                new Update().set("outcome", "FAILED").set("nextAttemptAt", until), "stripe_events");
    }
    /**
     * PAY-02: Stripe delivered again an event of this club recorded as FAILED (retrying or stopped). It goes back to the state of
     * a new receipt — no outcome, no attempts, due now — with the delivery's recovery data (a FAILED event stopped before 13 Nov
     * 2026 kept none). False for any other duplicate, which has no effect (R-12-21).
     */
    public boolean requeue(String id, Document work) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("outcome").is("FAILED")),
                new Update().set("work", work).unset("outcome").unset("attempts").unset("processedAt").unset("nextAttemptAt"),
                "stripe_events").getModifiedCount() == 1;
    }
    public void lock(String id) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().inc("processingSequence", 1), StripeEvent.class);
    }
    /** Dispatcher inventory only; processing always opens the stored club and re-reads through TenantRepository. */
    public List<StripeEvent> pending() { return mongo.find(Query.query(Criteria.where("processedAt").is(null)).addCriteria(PaymentRetryState.due(clock.instant()))
            .with(org.springframework.data.domain.Sort.by("receivedAt", "_id")).limit(100), StripeEvent.class); }
}
