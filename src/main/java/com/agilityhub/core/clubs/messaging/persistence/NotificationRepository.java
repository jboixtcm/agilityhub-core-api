package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository extends TenantRepository<Notification> {
    public NotificationRepository(MongoTemplate mongo) { super(mongo, Notification.class); }
    // Explicit SYSTEM operations allow null clubId; ordinary inherited methods always require a tenant.
    public Notification queue(Notification notification) {
        if (notification.clubId() != null) { return insert(notification); }
        requireSystem();
        return mongo.insert(notification);
    }
    public Optional<Notification> findSystem(String id) {
        requireSystem();
        return Optional.ofNullable(mongo.findOne(scoped(id), Notification.class));
    }
    public Optional<Notification> findScoped(String id) {
        return Optional.ofNullable(mongo.findOne(scoped(id), Notification.class));
    }
    public boolean finish(String id, Notification.Status status, String providerId, String error, Instant at) {
        var query = scoped(id).addCriteria(Criteria.where("status").is(Notification.Status.QUEUED));
        var update = new Update().set("status", status).set("providerMessageId", providerId).set("error", error);
        if (status == Notification.Status.SENT) { update.set("sentAt", at); }
        boolean transitioned = mongo.updateFirst(query, update, Notification.class).getModifiedCount() == 1;
        if (!transitioned && status == Notification.Status.SENT) {
            // A signed callback can arrive before the HTTP 202 response; retain acceptance metadata without regressing status.
            mongo.updateFirst(scoped(id), new Update().set("providerMessageId", providerId).set("sentAt", at), Notification.class);
        }
        return transitioned;
    }
    public boolean delivery(String id, Notification.Status status, String error) {
        var query = scoped(id).addCriteria(Criteria.where("status").in(status == Notification.Status.DELIVERED
                ? java.util.List.of(Notification.Status.QUEUED, Notification.Status.SENT)
                : java.util.List.of(Notification.Status.QUEUED, Notification.Status.SENT, Notification.Status.DELIVERED)));
        return mongo.updateFirst(query, new Update().set("status", status).set("error", error), Notification.class).getModifiedCount() == 1;
    }
    private Query scoped(String id) { return Query.query(Criteria.where("_id").is(id).and("clubId").is(TenantContext.current())); }
    private void requireSystem() {
        if (TenantContext.current() != null) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
    }
}
