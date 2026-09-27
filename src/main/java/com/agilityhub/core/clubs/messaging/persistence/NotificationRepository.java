package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

/**
 * `notifications` with the S11 §3 indexes. The E1–E6 helper rows carry one delivery and the compatibility block of
 * {@link Notification}; every transition below moves both in one update (`deliveries.0.*` and the flat fields). A row
 * written before E7-T01 (no `deliveries`) still moves its flat fields until `messaging:migrate-notifications` converts it.
 */
@Repository
public class NotificationRepository extends TenantRepository<Notification> {
    private final Clock clock;
    public NotificationRepository(MongoTemplate mongo, Clock clock) { super(mongo, Notification.class); this.clock = clock; }

    /**
     * S11 §3: `{clubId, dedupKey}` unique — partial on a string `dedupKey`, so rows written before E7-T01 (they have none)
     * never block the index on a local or staging database — and the feed, log and dispatcher indexes.
     */
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(Notification.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dedupKey", ASC).unique().named("notification_club_dedup")
                .partial(PartialIndexFilter.of(Criteria.where("dedupKey").type(2))));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("recipient.accountId", ASC).on("createdAt", DESC).named("notification_club_recipient_created"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("recipient.accountId", ASC).on("readAt", ASC).named("notification_club_recipient_read"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("createdAt", DESC).named("notification_club_created"));
        indexes.ensureIndex(new Index().on("deliveries.status", ASC).on("deliveries.nextAttemptAt", ASC).named("notification_delivery_due"));
    }

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
    /** A notification of the tenant addressed to the account (the feed's `read`, R-11-10): another account's or club's is absent. */
    public Optional<Notification> findForAccount(String id, String accountId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("recipient.accountId").is(accountId)), Notification.class));
    }
    public void appContent(String id,java.util.Map<String,Object> variables) { content(id,"APP",variables); }
    /** In-app notifications of the account in the tenant without `readAt` (the S08 03 bell): an `APP` delivery, never read. */
    public long unreadApp(String accountId) {
        return mongo.count(tenantQuery().addCriteria(Criteria.where("recipient.accountId").is(accountId).and("deliveries.channel").is("APP").and("readAt").is(null)),
                Notification.class);
    }
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
                "dogs_count","review_time","review_day","auto_cancel","audience","week_start","upfront_total","payment_instructions","instructor_name","task_excerpt")) if(variables.get(field)!=null) safe.put(field,variables.get(field));
        return safe;
    }
    /**
     * The SMS intent's phones and short text: `body` (read by the E4–E6 suites) and `smsBody`, both the rendered SMS. The
     * helper's `action`/`entityId` variables become the S11 `action {type, params: {entityId}}` (a known action type only).
     */
    public void smsContent(String id,java.util.List<String> phones,String body,java.util.Map<String,Object> variables) {
        var update=new Update().set("recipientPhones",phones).set("body",body).set("smsBody",body).set("entityId",variables.get("entityId"));
        String type=variables.get("action")==null?null:variables.get("action").toString();
        if(type!=null && Arrays.stream(NotificationActionType.values()).anyMatch(value -> value.name().equals(type))) {
            var params=new Document(); if(variables.get("entityId")!=null) params.put("entityId",variables.get("entityId").toString());
            update.set("action",new Document("type",type).append("params",params));
        }
        mongo.updateFirst(scoped(id).addCriteria(Criteria.where("channel").is("SMS")),update,Notification.class);
    }
    public boolean finish(String id, Notification.Status status, String providerId, String error, Instant at) {
        var query = scoped(id).addCriteria(Criteria.where("status").is(Notification.Status.QUEUED));
        boolean transitioned = move(query, update -> {
            update.set("status", status).set("providerMessageId", providerId).set("error", error);
            if (status == Notification.Status.SENT) { update.set("sentAt", at); }
        }, update -> {
            update.set("deliveries.0.status", status.name()).set("deliveries.0.providerRef", providerId).set("deliveries.0.lastError", error)
                    .inc("deliveries.0.attempts", 1);
            if (status == Notification.Status.SENT) { update.set("deliveries.0.sentAt", at); }
            if (status == Notification.Status.FAILED) { update.set("deliveries.0.failedAt", at); }
        });
        if (!transitioned && status == Notification.Status.SENT) {
            // A signed callback can arrive before the HTTP 202 response; retain acceptance metadata without regressing status.
            move(scoped(id), update -> update.set("providerMessageId", providerId).set("sentAt", at),
                    update -> update.set("deliveries.0.providerRef", providerId).set("deliveries.0.sentAt", at));
        }
        return transitioned;
    }
    public boolean delivery(String id, Notification.Status status, String error) {
        var query = scoped(id).addCriteria(Criteria.where("status").in(status == Notification.Status.DELIVERED
                ? java.util.List.of(Notification.Status.QUEUED, Notification.Status.SENT)
                : java.util.List.of(Notification.Status.QUEUED, Notification.Status.SENT, Notification.Status.DELIVERED)));
        Instant now = clock.instant();
        return move(query, update -> update.set("status", status).set("error", error), update -> {
            update.set("deliveries.0.status", status.name()).set("deliveries.0.lastError", error);
            update.set(status == Notification.Status.DELIVERED ? "deliveries.0.deliveredAt" : "deliveries.0.failedAt", now);
        });
    }
    /**
     * One conditional update of a compatibility row: the flat fields and its only delivery together; a row written before
     * E7-T01 (no `deliveries`) moves its flat fields only (setting `deliveries.0` there would create an object, not an array).
     */
    private boolean move(Query query, Consumer<Update> flat, Consumer<Update> delivery) {
        var both = new Update(); flat.accept(both); delivery.accept(both);
        if (mongo.updateFirst(Query.of(query).addCriteria(Criteria.where("deliveries.0").exists(true)), both, Notification.class).getModifiedCount() == 1) { return true; }
        var legacy = new Update(); flat.accept(legacy);
        return mongo.updateFirst(Query.of(query).addCriteria(Criteria.where("deliveries").exists(false)), legacy, Notification.class).getModifiedCount() == 1;
    }
    private Query scoped(String id) { return Query.query(Criteria.where("_id").is(id).and("clubId").is(TenantContext.current())); }
    private void requireSystem() {
        if (TenantContext.current() != null) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
    }
}
