package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
 * row is exactly what the helpers wrote since E7-T01: the S11 fields, one delivery, and its flat fields kept. Until it is
 * converted, {@link LegacyNotificationReader} maps its `action` the same way on every read.
 */
@Repository
public class LegacyNotificationRows {
    static final String COLLECTION = "notifications";
    /** The flat fields {@link #upgrade} reads: the conversion holds only while none of them changed since it read the row. */
    static final List<String> SOURCE_FIELDS = List.of("code", "channel", "status", "accountId", "recipientEmail", "variant", "providerMessageId", "error",
            "sentAt", "createdAt", "action", "entityId", "body");
    /** Conversions of one row tried while it keeps changing under the command; a rerun converts it later. */
    static final int ATTEMPTS = 5;
    private final MongoTemplate mongo;
    public LegacyNotificationRows(MongoTemplate mongo) { this.mongo = mongo; }

    /** Every club's and the SYSTEM rows, oldest first. A platform maintenance read: no tenant applies. */
    public List<Document> find() {
        return mongo.find(Query.query(Criteria.where("deliveries").exists(false)).with(Sort.by("createdAt", "_id")), Document.class, COLLECTION);
    }

    /**
     * Converts the row from its values at this moment (E7-T01 round 2), never from the command's listing: it reads the row
     * again, derives the S11 fields from what it holds now and writes them with one update conditioned on the row being
     * exactly as read — still without `deliveries`, every field it had with the same value and every {@link #SOURCE_FIELDS}
     * it lacked still absent. A webhook or a `finish` that moves the flat fields in between (QUEUED → DELIVERED) makes the
     * update miss, and the conversion runs again from the new values. `row` only names the row; it receives the values
     * converted. True when this call converted it; false when it was converted meanwhile, or kept changing for
     * {@value #ATTEMPTS} attempts (a rerun of the command converts it).
     */
    public boolean convert(Document row) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var current = mongo.findOne(Query.query(legacy(row.get("_id"))), Document.class, COLLECTION);
            if (current == null) { return false; }
            var update = new Update(); upgrade(current).forEach(update::set);
            if (mongo.updateFirst(unchanged(current), update, COLLECTION).getModifiedCount() == 1) { row.putAll(current); return true; }
        }
        return false;
    }
    private static Criteria legacy(Object id) { return Criteria.where("_id").is(id).and("deliveries").exists(false); }
    /** The row as read: `is(null)` also matches an absent field, the way {@link #upgrade} reads one. */
    private static Query unchanged(Document current) {
        var criteria = legacy(current.get("_id"));
        var fields = new LinkedHashSet<>(current.keySet()); fields.addAll(SOURCE_FIELDS); fields.remove("_id");
        for (String field : fields) { criteria.and(field).is(current.get(field)); }
        return Query.query(criteria);
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
            var action = action(row);
            fields.append("action", action);
            if (action == null) { fields.append("legacyAction", type); }
        }
        if ("SMS".equals(channel) && row.get("body") != null) { fields.append("smsBody", row.get("body")); }
        return fields;
    }

    /**
     * The S11 `action {type, params: {entityId}}` of a row whose `action` is the E4–E6 bare string (its `entityId` beside it),
     * or `null` for a name no {@link NotificationActionType} has. The conversion writes it; {@link LegacyNotificationReader}
     * maps a row not yet converted with it.
     */
    static Document action(Document row) {
        String type = row.getString("action");
        if (Arrays.stream(NotificationActionType.values()).noneMatch(value -> value.name().equals(type))) { return null; }
        var params = new Document(); if (row.get("entityId") != null) { params.append("entityId", row.get("entityId").toString()); }
        return new Document("type", type).append("params", params);
    }
}
