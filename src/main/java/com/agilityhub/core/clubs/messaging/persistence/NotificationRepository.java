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
        indexes.ensureIndex(new Index().on("deliveries.acceptedAt", ASC).named("notification_delivery_accepted")
                .partial(PartialIndexFilter.of(Criteria.where("deliveries.acceptedAt").exists(true))));
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

    // ---- Feed 11 (E7-T03, R-11-10): the account's notices with a delivered APP delivery, the same rows as the bell counts.

    private Query feedQuery(String accountId, com.agilityhub.core.clubs.messaging.domain.NotificationAudience audience) {
        var query = tenantQuery().addCriteria(Criteria.where("recipient.accountId").is(accountId)
                .and("deliveries").elemMatch(Criteria.where("channel").is("APP").and("status").in("DELIVERED", "SENT")));
        if (audience != null) { query.addCriteria(Criteria.where("audience").is(audience)); }
        return query;
    }
    /** One page of the feed, `createdAt desc` (then id, stable across pages). */
    public java.util.List<Notification> feed(String accountId, com.agilityhub.core.clubs.messaging.domain.NotificationAudience audience, int page, int size) {
        var query = feedQuery(accountId, audience).with(org.springframework.data.domain.Sort.by(DESC, "createdAt").and(org.springframework.data.domain.Sort.by(DESC, "_id")))
                .skip((long) page * size).limit(size);
        return mongo.find(query, Notification.class);
    }
    public long feedCount(String accountId, com.agilityhub.core.clubs.messaging.domain.NotificationAudience audience) {
        return mongo.count(feedQuery(accountId, audience), Notification.class);
    }
    /** `POST /me/notifications/{id}/read`: `readAt` once (idempotent: a read notice keeps its first `readAt`). */
    public void markRead(String id, String accountId, Instant at) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("recipient.accountId").is(accountId).and("readAt").is(null)),
                new Update().set("readAt", at), Notification.class);
    }
    /** `POST /me/notifications/read-all`: every unread notice of the account created until `upTo`. */
    public long markAllRead(String accountId, Instant upTo, Instant at) {
        return mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("recipient.accountId").is(accountId).and("readAt").is(null).and("createdAt").lte(upTo)),
                new Update().set("readAt", at), Notification.class).getModifiedCount();
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
     * known target, due (`nextAttemptAt ≤ now`), not leased by another sender and not accepted by a provider already
     * (`acceptedAt`, E7-T05: such a delivery is only settled, {@link #claimAccepted}); the delivery gets `claimToken` and
     * `claimedUntil = now + lease`. `notificationId = null` claims any due delivery of any club (the 5-second poll, no
     * tenant: each claimed document names its club); SYSTEM rows (their own synchronous path) are never claimed.
     */
    public Optional<Notification> claimDue(String notificationId, Instant now, java.time.Duration lease, String token) {
        var due = Criteria.where("status").is("QUEUED").and("target").ne(null).and("nextAttemptAt").lte(now).and("acceptedAt").is(null)
                .orOperator(Criteria.where("claimedUntil").is(null), Criteria.where("claimedUntil").lte(now));
        return claim(Query.query(Criteria.where("deliveries").elemMatch(due).and("category").ne("SYSTEM")), notificationId, now, lease, token);
    }
    /**
     * E7-T05 (R-11-09): the claim of a delivery the provider accepted whose settlement never completed — its sender's
     * settlement kept failing, or the sender stopped — once its lease expired. The claimant settles it with the stored
     * acceptance and never calls the provider again. Any status: a webhook may have moved it meanwhile (the settlement then
     * keeps that state).
     */
    public Optional<Notification> claimAccepted(String notificationId, Instant now, java.time.Duration lease, String token) {
        var accepted = Criteria.where("acceptedAt").ne(null).and("claimToken").ne(null).and("claimedUntil").lte(now);
        return claim(Query.query(Criteria.where("deliveries.acceptedAt").exists(true).and("deliveries").elemMatch(accepted).and("category").ne("SYSTEM")),
                notificationId, now, lease, token);
    }
    private Optional<Notification> claim(Query query, String notificationId, Instant now, java.time.Duration lease, String token) {
        if (notificationId != null) { query.addCriteria(Criteria.where("_id").is(notificationId).and("clubId").is(TenantContext.require())); }
        // The reset fence reads this same first row in the transaction snapshot. A total order keeps
        // a one-row claim from registering every matching tenant in the global delivery backlog.
        query.with(org.springframework.data.domain.Sort.by(ASC, "createdAt", "_id"));
        var update = new Update().set("deliveries.$.claimToken", token).set("deliveries.$.claimedUntil", now.plus(lease));
        return Optional.ofNullable(mongo.findAndModify(query, update,
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), Notification.class));
    }
    /**
     * E7-T05 (R-11-09): the provider accepted the claimed delivery's attempt. One small update, outside the settlement's
     * transaction and before it: `acceptedAt`, the `providerRef` when there is one, and the lease renewed from `at`. From then
     * on {@link #claimDue} never claims the delivery again; `false` when the lease is no longer this token's.
     */
    public boolean markAccepted(String id, String token, String providerRef, Instant at, java.time.Duration lease) {
        var update = new Update().set("deliveries.$.acceptedAt", at).set("deliveries.$.claimedUntil", at.plus(lease));
        if (providerRef != null) { update.set("deliveries.$.providerRef", providerRef); }
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("deliveries").elemMatch(Criteria.where("claimToken").is(token))), update, Notification.class)
                .getModifiedCount() == 1;
    }
    /** Writes the outcome of a claimed delivery (found by its lease token) and releases the lease and any acceptance mark; `false` if the lease was lost. */
    public boolean settle(String id, String token, Consumer<Update> outcome) {
        var update = new Update().unset("deliveries.$.claimToken").unset("deliveries.$.claimedUntil").unset("deliveries.$.acceptedAt");
        outcome.accept(update);
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("deliveries.claimToken").is(token)), update, Notification.class).getModifiedCount() == 1;
    }
    /** How {@link #settleClaim} found the claimed delivery. */
    public enum Settlement { MOVED, KEPT, LOST }
    /**
     * The dispatcher's settlement of a claimed delivery (R-11-08, S11 §5), run inside its transaction: `outcome` applies while
     * the delivery is still `QUEUED` (→ {@link Settlement#MOVED}); a delivery the SendGrid webhook already moved to its final
     * state while the provider call was in flight keeps that state and only gets `kept` (the acceptance's `providerRef`,
     * `sentAt` and attempt, as E1's `finish()` does) → {@link Settlement#KEPT}. Both release the lease; {@link Settlement#LOST}
     * when the lease is no longer this token's (nothing written).
     */
    public Settlement settleClaim(String id, String token, Consumer<Update> outcome, Consumer<Update> kept) {
        var queued = new Update().unset("deliveries.$.claimToken").unset("deliveries.$.claimedUntil").unset("deliveries.$.acceptedAt"); outcome.accept(queued);
        var stillQueued = Query.query(Criteria.where("_id").is(id).and("deliveries").elemMatch(Criteria.where("claimToken").is(token).and("status").is("QUEUED")));
        if (mongo.updateFirst(stillQueued, queued, Notification.class).getModifiedCount() == 1) { return Settlement.MOVED; }
        return settle(id, token, kept) ? Settlement.KEPT : Settlement.LOST;
    }
    /**
     * R-11-06 at the SMS cap: adds the forced EMAIL delivery to `delivery.target` only when the notification has no live EMAIL
     * delivery (`QUEUED`, `SENT`, `DELIVERED`) to that address, compared without case — one conditional `$push`, so two
     * dispatchers settling two phones of the same notification never both add it. `true` when it was added.
     */
    public boolean addEmailIfAbsent(String id, Notification.Delivery delivery) {
        var address = java.util.regex.Pattern.compile("^" + java.util.regex.Pattern.quote(delivery.target()) + "$", java.util.regex.Pattern.CASE_INSENSITIVE);
        var live = Criteria.where("deliveries").elemMatch(Criteria.where("channel").is("EMAIL").and("target").regex(address).and("status").in("QUEUED", "SENT", "DELIVERED"));
        var query = scoped(id).addCriteria(new Criteria().norOperator(live));
        return mongo.updateFirst(query, new Update().push("deliveries", delivery), Notification.class).getModifiedCount() == 1;
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
