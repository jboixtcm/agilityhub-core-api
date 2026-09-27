package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.FollowupKind;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/**
 * S10 §3 `followup_items`, the D14 projection (E6-T03): one row per task, whose id is derived from the task's, and one
 * MEMBER_NOTE row per dog, derived from the dog's, so a replayed event never writes a second row (T-10-18).
 */
@Repository
public class FollowupItemRepository extends TenantRepository<FollowupItem> {
    public FollowupItemRepository(MongoTemplate mongo) { super(mongo, FollowupItem.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(FollowupItem.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("activityAt", ASC).named("followup_club_activity"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("kind", ASC).on("activityAt", ASC).named("followup_club_kind_activity"));
    }
    public static String taskRowId(String taskId) { return UUID.nameUUIDFromBytes(("followup:task:" + taskId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    public static String noteRowId(String clubId, String dogId) {
        return UUID.nameUUIDFromBytes(("followup:note:" + clubId + ":" + dogId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private Query row(String id) { return tenantQuery().addCriteria(Criteria.where("_id").is(id)); }
    private void set(String id, Update update, Instant now) { mongo.updateFirst(row(id), update.set("updatedAt", now), FollowupItem.class); }

    /** The row of a new task: unread for everyone but its author (R-10-13), who is stored as they are now (E64). */
    public void task(String taskId, String dogId, String memberId, String authorAccountId, AuthorRole role, String authorName, String authorGender, String excerpt,
            Instant createdAt) {
        String id = taskRowId(taskId);
        mongo.upsert(row(id), new Update().setOnInsert("kind", FollowupKind.TASK).setOnInsert("taskId", taskId)
                .setOnInsert("dogId", dogId).setOnInsert("memberId", memberId).setOnInsert("authorAccountId", authorAccountId).setOnInsert("authorRole", role)
                .setOnInsert("authorName", authorName).setOnInsert("authorGender", authorGender).setOnInsert("textExcerpt", excerpt).setOnInsert("createdAt", createdAt)
                .setOnInsert("activityAt", createdAt).setOnInsert("hidden", false).setOnInsert("updatedAt", createdAt), FollowupItem.class);
    }
    /** A new text refreshes the excerpt only: the row keeps its `activityAt`, so it stays read (R-10-13). */
    public void excerpt(String taskId, String excerpt, Instant now) { set(taskRowId(taskId), new Update().set("textExcerpt", excerpt), now); }
    /** Completing sets `completedAt` and never `activityAt`; reopening clears it (R-10-13, §5). */
    public void completed(String taskId, Instant completedAt, Instant now) {
        set(taskRowId(taskId), completedAt == null ? new Update().unset("completedAt") : new Update().set("completedAt", completedAt), now);
    }
    /** `TaskDeleted` → the row is hidden, never removed (BR-12). */
    public void hide(String taskId, Instant now) { set(taskRowId(taskId), new Update().set("hidden", true), now); }
    /**
     * The one MEMBER_NOTE row of a dog (R-10-12/13): `activityAt` is the change's `occurredAt`, the excerpt the note's
     * current text, the author the member who wrote it (E64); an emptied note hides the row. Returns the row as stored
     * before, null the first time.
     */
    public FollowupItem note(String dogId, String memberId, String authorAccountId, String authorName, String authorGender, String excerpt, Instant occurredAt,
            Instant now) {
        String id = noteRowId(tenantClub(), dogId);
        var before = findById(id).orElse(null);
        mongo.upsert(row(id), new Update().setOnInsert("kind", FollowupKind.MEMBER_NOTE).setOnInsert("dogId", dogId)
                .setOnInsert("createdAt", occurredAt).set("memberId", memberId).set("authorAccountId", authorAccountId).set("authorRole", AuthorRole.MEMBER)
                .set("authorName", authorName).set("authorGender", authorGender).set("textExcerpt", excerpt).set("activityAt", occurredAt).set("hidden", excerpt.isEmpty()).set("updatedAt", now), FollowupItem.class);
        return before;
    }
    /** An older (replayed) note change: only the excerpt follows the note's current text. */
    public void noteText(String dogId, String excerpt, Instant now) {
        set(noteRowId(tenantClub(), dogId), new Update().set("textExcerpt", excerpt).set("hidden", excerpt.isEmpty()), now);
    }
    /** S03 R-03-14 `DogTransferred`: the dog's rows follow it to its current owner (idempotent); their authors stay. */
    public long transfer(String dogId, String memberId) {
        return mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("memberId").ne(memberId)),
                new Update().set("memberId", memberId), FollowupItem.class).getModifiedCount();
    }
    public List<FollowupItem> byIds(Collection<String> ids) { return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), FollowupItem.class); }
    /** `activityAt` of the given rows: what a read mark prunes against (R-10-13). */
    public Map<String, Instant> activity(Collection<String> ids) {
        var result = new HashMap<String, Instant>(); if (ids.isEmpty()) { return result; }
        var query = tenantQuery().addCriteria(Criteria.where("_id").in(ids)); query.fields().include("activityAt");
        mongo.find(query, org.bson.Document.class, "followup_items").forEach(item -> {
            var at = item.getDate("activityAt"); result.put(item.getString("_id"), at == null ? null : at.toInstant());
        });
        return result;
    }
    /** R-10-13 menu counter: visible rows unread for {@code me}, one count over `{clubId, activityAt}`. */
    public long unread(String me, Instant readAllAt, Collection<String> readItemIds) {
        var criteria = Criteria.where("hidden").is(false).and("authorAccountId").ne(me);
        if (readAllAt != null) { criteria = criteria.and("activityAt").gt(readAllAt); }
        if (!readItemIds.isEmpty()) { criteria = criteria.and("_id").nin(readItemIds); }
        return mongo.count(tenantQuery().addCriteria(criteria), FollowupItem.class);
    }
    private static String tenantClub() { return com.agilityhub.core.shared.application.TenantContext.require(); }
}
