package com.agilityhub.core.clubs.common.persistence;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * S15 R-15-19 P9: the only physical deletions of the product, on technical collections of one club, addressed by collection
 * name (they belong to other contexts, whose types this one does not import). Every query carries the club explicitly.
 */
@Repository
public class CleanupRepository {
    public static final String DOMAIN_EVENTS = "domain_events", STRIPE_EVENTS = "stripe_events", JOB_RUNS = "job_runs", UPLOADS = "attachment_uploads";
    private final MongoTemplate mongo;
    public CleanupRepository(MongoTemplate mongo) { this.mongo = mongo; }

    /** Documents whose TTL field is already past but that Mongo's TTL monitor has not removed yet (reported, never deleted). */
    public long ttlPending(String clubId, String collection, String field, Instant before) {
        var criteria = "job_locks".equals(collection) ? Criteria.where("_id").regex("^" + Pattern.quote(clubId + ":")) : Criteria.where("clubId").is(clubId);
        return mongo.count(Query.query(criteria.and(field).lt(before)), collection);
    }

    /** SIGNUP_DOCUMENT grants of the club older than `before` whose key no `DogDocument.files[].fileKey` references. */
    public List<String> orphanUploads(String clubId, Instant before) {
        var candidates = mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("purpose").is("SIGNUP_DOCUMENT")
                .and("_id").regex("^" + Pattern.quote("signup/" + clubId + "/")).and("createdAt").lt(before)).with(Sort.by("createdAt", "_id")), Document.class, UPLOADS)
                .stream().map(d -> d.getString("_id")).toList();
        if (candidates.isEmpty()) { return List.of(); }
        var referenced = new HashSet<String>();
        for (Document document : mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("files.fileKey").in(candidates)), Document.class, "dog_documents")) {
            for (Object file : document.getList("files", Object.class, List.of())) {
                if (file instanceof Document f && f.getString("fileKey") != null) { referenced.add(f.getString("fileKey")); }
            }
        }
        return candidates.stream().filter(key -> !referenced.contains(key)).toList();
    }
    public boolean deleteUpload(String clubId, String key) {
        return mongo.remove(Query.query(Criteria.where("_id").is(key).and("clubId").is(clubId)), UPLOADS).getDeletedCount() == 1;
    }

    /** Published (fully processed) outbox events of the club before `before`; PENDING/FAILED ones are never touched. */
    public long processedEvents(String clubId, Instant before) { return mongo.count(processed(clubId, before), DOMAIN_EVENTS); }
    public long deleteProcessedEvents(String clubId, Instant before) { return mongo.remove(processed(clubId, before), DOMAIN_EVENTS).getDeletedCount(); }
    private static Query processed(String clubId, Instant before) {
        return Query.query(Criteria.where("clubId").is(clubId).and("status").is("PUBLISHED").and("publishedAt").lt(before));
    }

    /** `stripe_events` arrives with S12 (E8): until the collection exists there is nothing to delete. */
    public boolean stripeEventsExist() { return mongo.collectionExists(STRIPE_EVENTS); }
    public long stripeEvents(String clubId, Instant before) { return mongo.count(stripe(clubId, before), STRIPE_EVENTS); }
    public long deleteStripeEvents(String clubId, Instant before) { return mongo.remove(stripe(clubId, before), STRIPE_EVENTS).getDeletedCount(); }
    private static Query stripe(String clubId, Instant before) { return Query.query(Criteria.where("clubId").is(clubId).and("receivedAt").lt(before)); }

    /** Runs of one process finished before `before`, except the `keep` most recent runs of that process (any status). */
    public List<String> expiredRuns(String clubId, String job, Instant before, int keep) {
        var latest = mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("job").is(job)).with(Sort.by(Sort.Direction.DESC, "startedAt", "_id")).limit(keep),
                Document.class, JOB_RUNS).stream().map(d -> d.getString("_id")).toList();
        return mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("job").is(job).and("finishedAt").lt(before).and("_id").nin(latest))
                .with(Sort.by("startedAt", "_id")), Document.class, JOB_RUNS).stream().map(d -> d.getString("_id")).toList();
    }
    public long deleteRuns(String clubId, Collection<String> ids) {
        if (ids.isEmpty()) { return 0; }
        return mongo.remove(Query.query(Criteria.where("clubId").is(clubId).and("_id").in(ids)), JOB_RUNS).getDeletedCount();
    }
}
