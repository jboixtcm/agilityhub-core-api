package com.agilityhub.core.clubs.messaging.persistence;

import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterLoadEvent;
import org.springframework.stereotype.Component;

/**
 * Reads the `notifications` rows written before E7-T01 without running `messaging:migrate-notifications` first (E7-T01
 * round 2). The E4–E6 SMS intent stored `action` as a bare string beside its `entityId`, where the S11 shape has
 * `action {type, params}`: before such a row is mapped, this listener rewrites the loaded document — never the stored one —
 * as the command converts it ({@link LegacyNotificationRows#action}), so every reader of {@link Notification} (`findScoped`,
 * the tenant's finds, the dispatcher's) loads it. The other flat fields already map: without `deliveries` the record has
 * `deliveries = null` and keeps its flat `status`.
 */
@Component
public class LegacyNotificationReader extends AbstractMongoEventListener<Notification> {
    @Override
    public void onAfterLoad(AfterLoadEvent<Notification> event) {
        Document row = event.getDocument();
        if (row != null && row.get("action") instanceof String) { row.put("action", LegacyNotificationRows.action(row)); }
    }
}
