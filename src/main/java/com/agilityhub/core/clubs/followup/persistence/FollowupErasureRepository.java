package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * S14 R-14-15, the follow-up part (S10): tasks keep ids, states and dates with `text → «(suprimit)»`; their attachments and the
 * dogs' instructor-note and observation attachments lose their name and are marked removed (their objects are deleted after the
 * commit); the D14 projection follows; upload grants of the erased files are deleted.
 */
@Repository
public class FollowupErasureRepository {
    private final MongoTemplate mongo;
    public FollowupErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }
    private static Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }

    public List<String> taskIds(Collection<String> memberIds, Collection<String> dogIds) {
        var query = Query.query(club().orOperator(Criteria.where("memberId").in(memberIds), Criteria.where("dogId").in(dogIds)));
        query.fields().include("_id");
        return mongo.find(query, Document.class, "tasks").stream().map(document -> document.getString("_id")).toList();
    }
    public long eraseTasks(Collection<String> taskIds, Collection<String> accountIds, String text, Instant at) {
        long changed = mongo.updateMulti(Query.query(club().and("_id").in(taskIds).and("text").ne(text)), new Update().set("text", text), "tasks").getModifiedCount();
        for (String actor : List.of("createdBy", "updatedBy", "doneBy", "deletedBy")) {
            changed += mongo.updateMulti(Query.query(club().and("_id").in(taskIds).and(actor + ".accountId").in(accountIds)),
                    new Update().unset(actor + ".displayName"), "tasks").getModifiedCount();
        }
        return changed;
    }
    /** The attachments of the tasks and of the dogs' notes: their keys (every one, removed or not: the object stays until now). */
    public List<String> eraseAttachments(Collection<String> taskIds, Collection<String> dogIds, Instant at) {
        var owned = Query.query(club().orOperator(Criteria.where("entityType").is("TASK").and("entityId").in(taskIds),
                Criteria.where("entityType").in("INSTRUCTOR_NOTE", "DOG_OBSERVATIONS").and("entityId").in(dogIds)));
        var keys = mongo.find(owned, Document.class, "attachments").stream().map(document -> document.getString("fileKey")).filter(java.util.Objects::nonNull).toList();
        mongo.updateMulti(owned, new Update().unset("name"), "attachments");
        mongo.updateMulti(Query.query(club().and("removedAt").is(null).orOperator(Criteria.where("entityType").is("TASK").and("entityId").in(taskIds),
                Criteria.where("entityType").in("INSTRUCTOR_NOTE", "DOG_OBSERVATIONS").and("entityId").in(dogIds))),
                new Update().set("removedAt", at), "attachments");
        return keys;
    }
    /** D14's projection rows of the members, dogs or tasks: the excerpt placeholder, and no author name of an erased account. */
    public long eraseItems(Collection<String> memberIds, Collection<String> dogIds, Collection<String> accountIds, String excerpt) {
        var rows = club().orOperator(Criteria.where("memberId").in(memberIds), Criteria.where("dogId").in(dogIds));
        long changed = mongo.updateMulti(Query.query(rows).addCriteria(Criteria.where("textExcerpt").ne(null)), new Update().set("textExcerpt", excerpt), "followup_items").getModifiedCount();
        return changed + mongo.updateMulti(Query.query(club().and("authorAccountId").in(accountIds)), new Update().unset("authorName"), "followup_items").getModifiedCount();
    }
    /**
     * Upload grants bound to the dogs or tasks (`dogId`, `dogId:type`, `taskId`) or opened by an erased account: their keys are the
     * uploaded objects; the grants are technical and go.
     */
    public List<String> deleteGrants(Collection<String> dogIds, Collection<String> taskIds, Collection<String> erasedAccountIds) {
        Set<String> bound = new LinkedHashSet<>(dogIds); bound.addAll(taskIds);
        var patterns = new ArrayList<Criteria>();
        patterns.add(Criteria.where("boundEntity").in(bound));
        for (String dog : dogIds) { patterns.add(Criteria.where("boundEntity").regex("^" + java.util.regex.Pattern.quote(dog) + ":")); }
        if (!erasedAccountIds.isEmpty()) { patterns.add(Criteria.where("accountId").in(erasedAccountIds)); }
        var query = Query.query(club().orOperator(patterns.toArray(Criteria[]::new)));
        var keys = mongo.find(query, Document.class, "attachment_uploads").stream().map(document -> document.getString("_id")).toList();
        mongo.remove(query, "attachment_uploads");
        return keys;
    }

    /** S14 R-14-13: the member's tasks (by member or dog) and the attachments of those tasks and of the dogs' notes, raw. */
    public List<Document> tasks(String memberId, Collection<String> dogIds) {
        return mongo.find(Query.query(club().orOperator(Criteria.where("memberId").is(memberId), Criteria.where("dogId").in(dogIds)))
                .with(org.springframework.data.domain.Sort.by("_id")), Document.class, "tasks");
    }
    public List<Document> attachments(Collection<String> taskIds, Collection<String> dogIds) {
        return mongo.find(Query.query(club().and("removedAt").is(null).orOperator(Criteria.where("entityType").is("TASK").and("entityId").in(taskIds),
                Criteria.where("entityType").in("INSTRUCTOR_NOTE", "DOG_OBSERVATIONS").and("entityId").in(dogIds))), Document.class, "attachments");
    }
}
