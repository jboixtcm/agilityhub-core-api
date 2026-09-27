package com.agilityhub.core.support;

import com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine;
import com.agilityhub.core.clubs.messaging.domain.NotificationEventEnvelope;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E7-T02: the S11 `notifications` documents read as the E1–E6 suites read their rows — one row per delivery (a Mongo
 * `$unwind` of `deliveries`), with the notification's `clubId`, `code`, `audience`, `category`, `accountId`
 * (`recipient.accountId`), `memberId`, `recipientEmail`, `locale`, `variables` (plus `action` = the action's type, as the
 * helpers stored it), `dedupKey`, `eventId`, `title`, `subject`, `action`, and the delivery's `channel`, `status`, `target`,
 * `attempts` and `body` (the SMS text on an SMS delivery, the rendered body otherwise). `_id` is the notification's. The
 * suites' own filters (`Criteria` on those row fields) apply unchanged, so they keep asserting the same observable outcome
 * (code, recipients, channels) on the new shape.
 */
public final class NotificationRows {
    private NotificationRows() { }

    /** Every row (delivery) matching `criteria`, which is written against the row fields above. */
    public static List<Document> find(MongoTemplate mongo, Criteria criteria) {
        var pipeline = new ArrayList<Document>();
        pipeline.add(new Document("$unwind", "$deliveries"));
        var variables = new Document("$cond", List.of(new Document("$ifNull", List.of("$action.type", false)),
                new Document("$mergeObjects", List.of(new Document("$ifNull", List.of("$variables", new Document())), new Document("action", "$action.type"))),
                new Document("$ifNull", List.of("$variables", new Document()))));
        pipeline.add(new Document("$project", new Document("clubId", 1).append("code", 1).append("audience", 1).append("category", 1).append("locale", 1)
                .append("dedupKey", 1).append("eventId", 1).append("eventType", 1).append("title", 1).append("subject", 1).append("action", 1)
                .append("readAt", 1).append("createdAt", 1).append("variables", variables)
                .append("accountId", "$recipient.accountId").append("memberId", "$recipient.memberId")
                // The E1 EMAIL row carried the address it went to.
                .append("recipientEmail", new Document("$cond", List.of(new Document("$eq", List.of("$deliveries.channel", "EMAIL")), "$deliveries.target", "$recipient.email")))
                .append("channel", "$deliveries.channel").append("status", "$deliveries.status").append("target", "$deliveries.target")
                .append("attempts", "$deliveries.attempts").append("lastError", "$deliveries.lastError").append("providerRef", "$deliveries.providerRef")
                .append("body", new Document("$cond", List.of(new Document("$eq", List.of("$deliveries.channel", "SMS")), "$smsBody", "$body")))));
        if (criteria != null) { pipeline.add(new Document("$match", criteria.getCriteriaObject())); }
        return mongo.getCollection("notifications").aggregate(pipeline).into(new ArrayList<>());
    }
    public static long count(MongoTemplate mongo, Criteria criteria) { return find(mongo, criteria).size(); }

    /** The club's rows matching `criteria` (notification- or delivery-level row fields). */
    public static List<Document> rows(MongoTemplate mongo, String clubId, Criteria criteria) {
        var all = Criteria.where("clubId").is(clubId);
        return find(mongo, criteria == null ? all : new Criteria().andOperator(all, criteria));
    }
    public static List<Document> rows(MongoTemplate mongo, String clubId, String code) { return rows(mongo, clubId, Criteria.where("code").is(code)); }
    /**
     * The rows an E1–E6 helper would have written: a delivery without any destination (`SKIPPED_NO_CONTACT`: no account for
     * APP, no phone, no push subscription) was no row then.
     */
    public static List<Document> live(MongoTemplate mongo, String clubId, String code, String channel) {
        return rows(mongo, clubId, Criteria.where("code").is(code).and("channel").is(channel).and("status").ne("SKIPPED_NO_CONTACT"));
    }
    /** The club's notification documents of a code (one per recipient and dog). */
    public static List<Document> notifications(MongoTemplate mongo, String clubId, String code) {
        return mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("code").is(code)), Document.class, "notifications");
    }
    /** Whether the text is made of the GSM-7 basic alphabet only (R-11-06 transliteration). */
    public static boolean gsm7(String text) { return com.agilityhub.core.clubs.messaging.domain.SmsText.gsm7(text).equals(text); }

    /** An event handed to the engine as the outbox delivers it: inside a transaction, the sends right after its commit. */
    public static void deliver(NotificationEngine engine, PlatformTransactionManager transactions, String eventId, DomainEvent event) {
        var envelope = new NotificationEventEnvelope(null, event.type(), event.clubId(), event.aggregateType(), event.aggregateId(), event.occurredAt(),
                event.payload(), event.actorAccountId(), event.impersonatedMemberId(), event.origin());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> engine.handle(eventId, envelope));
    }
}
