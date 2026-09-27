package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/**
 * S10 §3 `tasks` (E6-T03). Every state change is one conditional `findAndModify` on the current state and `deletedAt: null`
 * (and on `version` for the text), which bumps `version`: two concurrent completions change the task once (T-10-25).
 */
@Repository
public class TaskRepository extends TenantRepository<Task> {
    public TaskRepository(MongoTemplate mongo) { super(mongo, Task.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(Task.class).ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("deletedAt", ASC).on("state", ASC).on("createdAt", ASC)
                .named("task_club_dog_deleted_state_created"));
    }
    private Query live(String id) { return tenantQuery().addCriteria(Criteria.where("_id").is(id).and("deletedAt").is(null)); }
    private Optional<Task> modify(Query query, Update update, Instant now) {
        return Optional.ofNullable(mongo.findAndModify(query, update.set("updatedAt", now).inc("version", 1), FindAndModifyOptions.options().returnNew(true), Task.class));
    }
    /** R-10-10: a new text; empty when the task is deleted or its `version` moved on. */
    public Optional<Task> updateText(String id, long version, String text, Task.Actor by, Instant now) {
        return modify(live(id).addCriteria(Criteria.where("version").is(version)), new Update().set("text", text).set("updatedBy", by), now);
    }
    /** PENDING → DONE; empty when the task is not a live PENDING one (anymore). */
    public Optional<Task> complete(String id, Task.Actor by, Instant now) {
        return modify(live(id).addCriteria(Criteria.where("state").is(TaskState.PENDING)),
                new Update().set("state", TaskState.DONE).set("doneAt", now).set("doneBy", by), now);
    }
    /** DONE → PENDING (§13-12), clearing `doneAt`/`doneBy`; empty when the task is not a live DONE one. */
    public Optional<Task> reopen(String id, Task.Actor by, Instant now) {
        return modify(live(id).addCriteria(Criteria.where("state").is(TaskState.DONE)),
                new Update().set("state", TaskState.PENDING).unset("doneAt").unset("doneBy").set("updatedBy", by), now);
    }
    /** The logical deletion (BR-12): `deletedAt` + `deletedBy`, also of a DONE task; empty when it was deleted already. */
    public Optional<Task> delete(String id, Task.Actor by, Instant now) {
        return modify(live(id), new Update().set("deletedAt", now).set("deletedBy", by), now);
    }
    /**
     * `Task.attachmentCount`, kept by the attachment writer (S10 §3). Written by collection name, so the text's `version`
     * stays (an attachment is no text edit).
     */
    public void countAttachments(String id, int delta) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().inc("attachmentCount", delta), "tasks");
    }
    /** `GET /tasks`: a dog's tasks in the given states, newest first; deleted ones only when asked (ADMIN). */
    public List<Task> forDog(String dogId, Collection<TaskState> states, boolean includeDeleted, int page, int size) {
        var query = tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("state").in(states));
        if (!includeDeleted) { query.addCriteria(Criteria.where("deletedAt").is(null)); }
        return mongo.find(query.with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("_id"))).skip((long) page * size).limit(size), Task.class);
    }
    /** One aggregation over the index for the sheet rows (R-10-02 `pendingTasksCount`): live PENDING tasks per dog. */
    public Map<String, Integer> pendingCounts(Collection<String> dogIds) {
        var result = new HashMap<String, Integer>(); if (dogIds.isEmpty()) { return result; }
        var pipeline = List.of(new Document("$match", tenantQuery().addCriteria(Criteria.where("dogId").in(new HashSet<>(dogIds)).and("deletedAt").is(null)
                        .and("state").is(TaskState.PENDING.name())).getQueryObject()),
                new Document("$group", new Document("_id", "$dogId").append("count", new Document("$sum", 1))));
        mongo.getCollection("tasks").aggregate(pipeline).forEach(row -> result.put(row.getString("_id"), ((Number) row.get("count")).intValue()));
        return result;
    }
    /** The card's «n pendents · n fetes» (22/D13): live tasks per state. */
    public Map<TaskState, Integer> counts(String dogId) {
        var result = new EnumMap<TaskState, Integer>(TaskState.class);
        for (var state : TaskState.values()) {
            result.put(state, (int) mongo.count(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("deletedAt").is(null).and("state").is(state)), Task.class));
        }
        return result;
    }
    /** The latest live task of a dog (the card's «darrera tasca»). */
    public Optional<Task> latest(String dogId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("deletedAt").is(null))
                .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "_id"))), Task.class));
    }
    /** S03 R-03-14 `DogTransferred`: the task follows the dog, so its `memberId` becomes the new owner (idempotent). */
    public long transfer(String dogId, String memberId) {
        return mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("memberId").ne(memberId)),
                new Update().set("memberId", memberId), "tasks").getModifiedCount();
    }
}
