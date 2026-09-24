package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
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
 * S15 R-15-19 P9: the only physical deletions of the product, on technical collections of one club. The collections
 * belong to other contexts (followup, census, shared outbox, platform jobs, payments), whose entity types this one does
 * not import, so they are addressed by name instead of through a {@code TenantRepository} subclass. Every query is
 * nevertheless bound the same way: {@link #scope} requires the open {@link TenantContext} (the job runner opens the
 * club's) and refuses another club with {@code TENANT_MISMATCH}, exactly like {@code TenantRepository.tenantQuery(clubId)}.
 */
@Repository
public class CleanupRepository {
    public static final String DOMAIN_EVENTS = "domain_events", STRIPE_EVENTS = "stripe_events", JOB_RUNS = "job_runs", UPLOADS = "attachment_uploads";
    private final MongoTemplate mongo;
    public CleanupRepository(MongoTemplate mongo) { this.mongo = mongo; }

    /** `clubId = <tenant>`; the tenant is the one of the open context and must be `clubId`. */
    private static Criteria scope(String clubId) { return Criteria.where("clubId").is(tenant(clubId)); }
    private static String tenant(String clubId) {
        String tenant = TenantContext.require();
        if (!tenant.equals(clubId)) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
        return tenant;
    }

    /**
     * Documents whose TTL field is already past but that Mongo's TTL monitor has not removed yet (reported, never
     * deleted). `job_locks` has no `clubId`: its ids are `{clubId}:{job}`, so the tenant is the id prefix.
     */
    public long ttlPending(String clubId, String collection, String field, Instant before) {
        var criteria = "job_locks".equals(collection) ? Criteria.where("_id").regex("^" + Pattern.quote(tenant(clubId) + ":")) : scope(clubId);
        return mongo.count(Query.query(criteria.and(field).lt(before)), collection);
    }

    /** SIGNUP_DOCUMENT grants of the club older than `before` whose key no `DogDocument.files[].fileKey` references. */
    public List<String> orphanUploads(String clubId, Instant before) {
        var candidates = mongo.find(Query.query(scope(clubId).and("purpose").is("SIGNUP_DOCUMENT")
                .and("_id").regex("^" + Pattern.quote("signup/" + clubId + "/")).and("createdAt").lt(before)).with(Sort.by("createdAt", "_id")), Document.class, UPLOADS)
                .stream().map(d -> d.getString("_id")).toList();
        if (candidates.isEmpty()) { return List.of(); }
        var referenced = new HashSet<String>();
        for (Document document : mongo.find(Query.query(scope(clubId).and("files.fileKey").in(candidates)), Document.class, "dog_documents")) {
            for (Object file : document.getList("files", Object.class, List.of())) {
                if (file instanceof Document f && f.getString("fileKey") != null) { referenced.add(f.getString("fileKey")); }
            }
        }
        return candidates.stream().filter(key -> !referenced.contains(key)).toList();
    }
    public boolean deleteUpload(String clubId, String key) {
        return mongo.remove(Query.query(scope(clubId).and("_id").is(key)), UPLOADS).getDeletedCount() == 1;
    }

    /**
     * Published (fully processed) outbox events of the club before `before`; PENDING/FAILED ones are never touched.
     * Events without a `clubId` (platform, identity) are outside every club's cleanup.
     */
    public long processedEvents(String clubId, Instant before) { return mongo.count(processed(clubId, before), DOMAIN_EVENTS); }
    public long deleteProcessedEvents(String clubId, Instant before) { return mongo.remove(processed(clubId, before), DOMAIN_EVENTS).getDeletedCount(); }
    private static Query processed(String clubId, Instant before) {
        return Query.query(scope(clubId).and("status").is("PUBLISHED").and("publishedAt").lt(before));
    }

    /** `stripe_events` arrives with S12 (E8): until the collection exists there is nothing to delete. */
    public boolean stripeEventsExist() { return mongo.collectionExists(STRIPE_EVENTS); }
    public long stripeEvents(String clubId, Instant before) { return mongo.count(stripe(clubId, before), STRIPE_EVENTS); }
    public long deleteStripeEvents(String clubId, Instant before) { return mongo.remove(stripe(clubId, before), STRIPE_EVENTS).getDeletedCount(); }
    private static Query stripe(String clubId, Instant before) { return Query.query(scope(clubId).and("receivedAt").lt(before)); }

    /**
     * Runs of one process finished before `before`, except the `keep` most recent **executions** of that process: real
     * runs that did work (not dry runs, not `SKIPPED` rows), so an admin's simulations never push the history out.
     */
    public List<String> expiredRuns(String clubId, String job, Instant before, int keep) {
        var latest = mongo.find(Query.query(scope(clubId).and("job").is(job).and("dryRun").ne(true).and("status").ne("SKIPPED"))
                .with(Sort.by(Sort.Direction.DESC, "startedAt", "_id")).limit(keep), Document.class, JOB_RUNS).stream().map(d -> d.getString("_id")).toList();
        return mongo.find(Query.query(scope(clubId).and("job").is(job).and("finishedAt").lt(before).and("_id").nin(latest))
                .with(Sort.by("startedAt", "_id")), Document.class, JOB_RUNS).stream().map(d -> d.getString("_id")).toList();
    }
    public long deleteRuns(String clubId, Collection<String> ids) {
        if (ids.isEmpty()) { return 0; }
        return mongo.remove(Query.query(scope(clubId).and("_id").in(ids)), JOB_RUNS).getDeletedCount();
    }
}
