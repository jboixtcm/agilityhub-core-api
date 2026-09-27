package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * The E1–E6 flat rows written before E7-T01 (no `deliveries`) and their conversion into the S11 §3 shape, for
 * `messaging:migrate-notifications` on local and staging databases (R1 is not live: no production row exists). A converted
 * row is exactly what the helpers write since E7-T01: the S11 fields, one delivery, and its flat fields kept.
 */
@Repository
public class LegacyNotificationRows {
    private final MongoTemplate mongo;
    public LegacyNotificationRows(MongoTemplate mongo) { this.mongo = mongo; }

    /** Every club's and the SYSTEM rows, oldest first. A platform maintenance read: no tenant applies. */
    public List<Document> find() {
        return mongo.find(Query.query(Criteria.where("deliveries").exists(false)).with(Sort.by("createdAt", "_id")), Document.class, "notifications");
    }
    /** Converts the row in place while it still has no `deliveries` (idempotent); true when this call converted it. */
    public boolean convert(Document row) {
        var update = new Update(); upgrade(row).forEach(update::set);
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(row.get("_id")).and("deliveries").exists(false)), update, "notifications").getModifiedCount() == 1;
    }

    /** The fields a flat row gains (the `$set` of {@link #convert}); the flat fields themselves stay. */
    public static Document upgrade(Document row) {
        String code = row.getString("code"), channel = Objects.requireNonNullElse(row.getString("channel"), "EMAIL"), status = row.getString("status");
        String accountId = row.getString("accountId"), email = row.getString("recipientEmail");
        var spec = NotificationCatalog.byCode(code);
        var fields = new Document("dedupKey", row.get("_id").toString());
        spec.ifPresent(s -> fields.append("category", s.category().name()).append("icon", s.icon().name()).append("color", s.color().name()));
        var audience = Notification.legacyAudience(code, accountId, row.getString("variant"));
        if (audience != null) { fields.append("audience", audience.name()); }
        fields.append("recipient", new Document("accountId", accountId).append("email", email));
        boolean attempted = "EMAIL".equals(channel) && List.of("SENT", "DELIVERED", "FAILED").contains(status);
        fields.append("deliveries", List.of(new Document("channel", channel).append("target", Notification.target(channel, accountId, email))
                .append("status", status).append("attempts", attempted ? 1 : 0).append("providerRef", row.get("providerMessageId"))
                .append("lastError", row.get("error")).append("sentAt", row.get("sentAt"))
                .append("deliveredAt", "DELIVERED".equals(status) ? row.get("sentAt") : null)
                .append("failedAt", "FAILED".equals(status) ? row.get("createdAt") : null)));
        // The E4–E6 SMS intent kept `action` as a bare string; S11 has `action {type, params}` (an unknown name stays as `legacyAction`).
        if (row.get("action") instanceof String type) {
            if (Arrays.stream(NotificationActionType.values()).anyMatch(value -> value.name().equals(type))) {
                var params = new Document(); if (row.get("entityId") != null) { params.append("entityId", row.get("entityId").toString()); }
                fields.append("action", new Document("type", type).append("params", params));
            } else { fields.append("action", null).append("legacyAction", type); }
        }
        if ("SMS".equals(channel) && row.get("body") != null) { fields.append("smsBody", row.get("body")); }
        return fields;
    }
}
