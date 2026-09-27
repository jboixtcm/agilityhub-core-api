package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
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
 * `notifications` with the S11 §3 indexes, the engine's upsert by `dedupKey`, the dispatcher's claim and outcome writes (E7-T02),
 * and the SYSTEM rows of the E1 path, which carry one delivery and the compatibility block of {@link Notification}: their
 * transitions move both in one update (`deliveries.0.*` and the flat fields). A row written before E7-T01 (no `deliveries`)
 * still moves its flat fields until `messaging:migrate-notifications` converts it.
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
    /**
     * In-app notifications of the account in the tenant without `readAt` (the S08 03 bell): a delivered `APP` delivery (a
     * stale reminder's skipped APP delivery is no notice, R-11-16), never read.
     */
    public long unreadApp(String accountId) {
        return mongo.count(tenantQuery().addCriteria(Criteria.where("recipient.accountId").is(accountId).and("readAt").is(null)
                .and("deliveries").elemMatch(Criteria.where("channel").is("APP").and("status").in("DELIVERED", "SENT"))), Notification.class);
    }

    // ---- The engine (E7-T02): upsert by dedupKey, deliveries added if absent.

    /** The tenant's notifications with these `dedupKey`s, by key (one `$in` read per 500 keys). */
    public java.util.Map<String, Notification> byDedupKeys(java.util.Collection<String> keys) {
        var found = new java.util.HashMap<String, Notification>();
        var list = java.util.List.copyOf(new java.util.LinkedHashSet<>(keys));
        for (int from = 0; from < list.size(); from += 500) {
            var slice = list.subList(from, Math.min(list.size(), from + 500));
            mongo.find(tenantQuery().addCriteria(Criteria.where("dedupKey").in(slice)), Notification.class).forEach(n -> found.put(n.dedupKey(), n));
        }
        return found;
    }
    /** S15 R-15-11 «pic de càrrega»: new notifications of the tenant in batches of 500 (`insertMany`). */
    public void insertAll(java.util.List<Notification> fresh) {
        String club = TenantContext.require();
        for (var notification : fresh) { if (!club.equals(notification.clubId())) { throw new ApiException(ErrorCode.TENANT_MISMATCH); } }
        for (int from = 0; from < fresh.size(); from += 500) { mongo.insert(fresh.subList(from, Math.min(fresh.size(), from + 500)), Notification.class); }
    }
    /** Deliveries a reprocessed event or the SMS cap adds to an existing notification of the tenant. */
    public void addDeliveries(String id, java.util.List<Notification.Delivery> deliveries) {
        if (deliveries.isEmpty()) { return; }
        mongo.updateFirst(scoped(id), new Update().push("deliveries").each(deliveries.toArray()), Notification.class);
    }
    /**
     * The dispatcher's claim (S11 R-11-09, T-11-30): one atomic `findAndModify` of a notification with a `QUEUED` delivery to a
     * known target, due (`nextAttemptAt ≤ now`) and not leased by another sender; the delivery gets `claimToken` and
     * `claimedUntil = now + lease`. `notificationId = null` claims any due delivery of any club (the 5-second poll, no
     * tenant: each claimed document names its club); SYSTEM rows (their own synchronous path) are never claimed.
     */
    public Optional<Notification> claimDue(String notificationId, Instant now, java.time.Duration lease, String token) {
        var due = Criteria.where("status").is("QUEUED").and("target").ne(null).and("nextAttemptAt").lte(now)
                .orOperator(Criteria.where("claimedUntil").is(null), Criteria.where("claimedUntil").lte(now));
        var query = Query.query(Criteria.where("deliveries").elemMatch(due).and("category").ne("SYSTEM"));
        if (notificationId != null) { query.addCriteria(Criteria.where("_id").is(notificationId).and("clubId").is(TenantContext.require())); }
        var update = new Update().set("deliveries.$.claimToken", token).set("deliveries.$.claimedUntil", now.plus(lease));
        return Optional.ofNullable(mongo.findAndModify(query, update,
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), Notification.class));
    }
    /** Writes the outcome of a claimed delivery (found by its lease token) and releases the lease; `false` if the lease was lost. */
    public boolean settle(String id, String token, Consumer<Update> outcome) {
        var update = new Update().unset("deliveries.$.claimToken").unset("deliveries.$.claimedUntil");
        outcome.accept(update);
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("deliveries.claimToken").is(token)), update, Notification.class).getModifiedCount() == 1;
    }
    /** Any notification by id, whatever the tenant (the poll, R-11-09), for the dispatcher only. */
    public Optional<Notification> findForDispatch(String id) { return Optional.ofNullable(mongo.findById(id, Notification.class)); }
    /**
     * The SendGrid webhook of an engine e-mail (R-11-08): moves the EMAIL delivery of the notification to `target`,
     * `DELIVERED` from `QUEUED`/`SENT`, `FAILED` from `QUEUED`/`SENT`/`DELIVERED`; `false` when there is no such delivery.
     */
    public boolean emailOutcome(String id, String target, DeliveryStatusChange change) {
        var from = change.status() == com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.DELIVERED ? java.util.List.of("QUEUED", "SENT") : java.util.List.of("QUEUED", "SENT", "DELIVERED");
        var query = scoped(id).addCriteria(Criteria.where("deliveries").elemMatch(Criteria.where("channel").is("EMAIL").and("target").is(target).and("status").in(from)));
        var update = new Update().set("deliveries.$.status", change.status().name());
        if (change.status() == com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.DELIVERED) { update.set("deliveries.$.deliveredAt", change.at()); }
        else { update.set("deliveries.$.failedAt", change.at()).set("deliveries.$.lastError", change.error()); }
        return mongo.updateFirst(query, update, Notification.class).getModifiedCount() == 1;
    }
    public record DeliveryStatusChange(com.agilityhub.core.clubs.messaging.domain.DeliveryStatus status, String error, Instant at) { }
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
