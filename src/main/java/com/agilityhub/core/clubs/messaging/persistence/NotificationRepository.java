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
    public void appContent(String id,java.util.Map<String,Object> variables) { content(id,"APP",variables); }
    /** Ids of the given ones already queued in the tenant (a batch never writes a row twice). */
    public java.util.Set<String> existingIds(java.util.Collection<String> ids) {
        if(ids.isEmpty()) return java.util.Set.of();
        var found=new java.util.HashSet<String>();
        mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)),Notification.class).forEach(n -> found.add(n.id()));
        return found;
    }
    /** S15 fan-out: one `insertMany` for a batch of APP/PUSH rows of the tenant with their allow-listed variables. */
    public void insertBatch(java.util.List<Notification> rows,java.util.Map<String,java.util.Map<String,Object>> variables) {
        if(rows.isEmpty()) return;
        String club=com.agilityhub.core.shared.application.TenantContext.require();
        var documents=new java.util.ArrayList<org.bson.Document>();
        for(var row:rows) {
            if(!club.equals(row.clubId())) throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.TENANT_MISMATCH);
            var document=new org.bson.Document(); mongo.getConverter().write(row,document);
            document.put("variables",safe(variables.getOrDefault(row.id(),java.util.Map.of()))); documents.add(document);
        }
        mongo.insert(documents,"notifications");
    }
    /** Renderable variables of an APP or PUSH row (allow-listed: never tokens or contact data). */
    public void content(String id,String channel,java.util.Map<String,Object> variables) {
        mongo.updateFirst(scoped(id).addCriteria(Criteria.where("channel").is(channel)),new Update().set("variables",safe(variables)),Notification.class);
    }
    private static java.util.Map<String,Object> safe(java.util.Map<String,Object> variables) {
        var safe=new java.util.LinkedHashMap<String,Object>();
        for(String field:java.util.List.of("member_name","member_first_name","gender","club_name","dogs","plan_name","dog_name","reason","entityId","action","class_date","class_time","class_description","admin_text","changes","activity_title","date","state","ring_name","calendar_links","late","actor","change","confirm_by","mode","time","has_admin_text",
                "dogs_count","review_time","review_day","auto_cancel","week_start")) if(variables.get(field)!=null) safe.put(field,variables.get(field));
        return safe;
    }
    public void smsContent(String id,java.util.List<String> phones,String body,java.util.Map<String,Object> variables) {
        mongo.updateFirst(scoped(id).addCriteria(Criteria.where("channel").is("SMS")),new Update().set("recipientPhones",phones).set("body",body)
                .set("entityId",variables.get("entityId")).set("action",variables.get("action")),Notification.class);
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
