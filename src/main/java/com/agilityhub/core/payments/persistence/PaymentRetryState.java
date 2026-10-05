package com.agilityhub.core.payments.persistence;

import java.time.Instant;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;

/** Internal delivery checkpoints, shared by the Stripe inbox and durable provider commands. */
final class PaymentRetryState {
    private PaymentRetryState() { }
    static Criteria due(Instant now) {
        return new Criteria().orOperator(Criteria.where("nextAttemptAt").is(null), Criteria.where("nextAttemptAt").lte(now));
    }
    static boolean failed(MongoTemplate mongo, Query query, String collection, Instant now, int max) {
        var row = mongo.findAndModify(query.addCriteria(Criteria.where("processedAt").is(null)),
                new Update().inc("attempts", 1).set("outcome", "FAILED"), FindAndModifyOptions.options().returnNew(true), Document.class, collection);
        if (row == null) { return false; }
        int attempts = ((Number) row.get("attempts")).intValue();
        boolean exhausted = attempts >= max;
        var update = exhausted ? new Update().set("processedAt", now).unset("nextAttemptAt").unset("work")
                : new Update().set("nextAttemptAt", now.plusSeconds(Math.min(300L, 10L << Math.min(attempts - 1, 5))));
        mongo.updateFirst(query, update, collection);
        return exhausted;
    }
}
