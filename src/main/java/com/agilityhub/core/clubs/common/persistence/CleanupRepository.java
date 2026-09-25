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
    public long processedEvents(String clubId, Instant before) { return mongo.count(processed(scope(clubId), before), DOMAIN_EVENTS); }
    public long deleteProcessedEvents(String clubId, Instant before) { return mongo.remove(processed(scope(clubId), before), DOMAIN_EVENTS).getDeletedCount(); }
    private static Query processed(Criteria owner, Instant before) {
        return Query.query(owner.and("status").is("PUBLISHED").and("publishedAt").lt(before));
    }

    /*
     * The platform pass (organizer ruling of 24-09 on E5-T05 round 2): the `domain_events` and `job_runs` with no
     * `clubId` (platform and identity rows), with the club pass's rules, once per P9 cycle. `platform()` matches only
     * rows without a `clubId`, so this pass can never touch a club row.
     */
    static final String PLATFORM_CYCLE = "platform:CLEANUP";
    private static Criteria platform() { return Criteria.where("clubId").is(null); }

    /**
     * The P9 cycle is the UTC day of the occurrence: the first real P9 run of that day (any club) claims the platform
     * pass until the day ends; every other run of the same day, and a late catch-up of an older day, gets false. The
     * claim is a `job_locks` lease (`holder` = the club whose run does the pass, `runId` = that run), so Mongo's TTL
     * removes it afterwards. A claim whose run is FAILED is free for the next run of the same day, even when the FAILED
     * run's release ({@link #releasePlatformCycle}) never ran (E5-T15).
     */
    public boolean claimPlatformCycle(String clubId, Instant occurrence, String runId) {
        String holder = tenant(clubId);
        var cycleEnd = cycleEnd(occurrence);
        var update = new org.springframework.data.mongodb.core.query.Update().set("holder", holder).set("runId", runId).set("acquiredAt", occurrence)
                .set("expiresAt", cycleEnd);
        try {
            var query = Query.query(Criteria.where("_id").is(PLATFORM_CYCLE).and("expiresAt").lte(occurrence));
            return mongo.findAndModify(query, update, org.springframework.data.mongodb.core.FindAndModifyOptions.options().upsert(true).returnNew(true),
                    Document.class, "job_locks") != null;
        } catch (org.springframework.dao.DuplicateKeyException claimed) {
            // E5-T15 (review E5-T13 #3): held, but perhaps by a run of this cycle that ended FAILED and never gave it back (a
            // crash between its FAILED commit and the release hook, or a failed hook). Take over exactly that claim.
            var held = failedClaim(cycleEnd);
            if (held == null) { return false; }
            var takeover = Query.query(Criteria.where("_id").is(PLATFORM_CYCLE).and("holder").is(held.getString("holder"))
                    .and("runId").is(held.getString("runId")).and("expiresAt").is(cycleEnd));
            return mongo.findAndModify(takeover, update, org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                    Document.class, "job_locks") != null;
        }
    }
    private static Instant cycleEnd(Instant occurrence) {
        return occurrence.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }
    /**
     * The claim of this cycle (`expiresAt = cycleEnd`) when the run that holds it is FAILED, else null. That run belongs to
     * the holder club, which may be another one: the platform pass is shared by every club, and only its status is read.
     */
    private Document failedClaim(Instant cycleEnd) {
        var held = mongo.findOne(Query.query(Criteria.where("_id").is(PLATFORM_CYCLE).and("expiresAt").is(cycleEnd)), Document.class, "job_locks");
        if (held == null || held.getString("holder") == null || held.getString("runId") == null) { return null; }
        boolean failed = mongo.exists(Query.query(Criteria.where("_id").is(held.getString("runId")).and("clubId").is(held.getString("holder"))
                .and("status").is("FAILED")), JOB_RUNS);
        return failed ? held : null;
    }
    /**
     * E5-T13 (review E5-T10 #4): the run that claimed the cycle ended FAILED, so the claim is given back and another run of
     * the same UTC day (another club's, or this club's retake) can do the pass. Only that run's claim is removed.
     */
    public boolean releasePlatformCycle(String clubId, String runId) {
        var query = Query.query(Criteria.where("_id").is(PLATFORM_CYCLE).and("holder").is(tenant(clubId)).and("runId").is(runId));
        return mongo.remove(query, "job_locks").getDeletedCount() == 1;
    }
    /** For a dry run: would a real run of this occurrence still get the platform pass? (claims nothing) */
    public boolean platformCycleOpen(Instant occurrence) {
        return !mongo.exists(Query.query(Criteria.where("_id").is(PLATFORM_CYCLE).and("expiresAt").gt(occurrence)), "job_locks")
                || failedClaim(cycleEnd(occurrence)) != null;
    }
    public long processedPlatformEvents(Instant before) { return mongo.count(processed(platform(), before), DOMAIN_EVENTS); }
    public long deleteProcessedPlatformEvents(Instant before) { return mongo.remove(processed(platform(), before), DOMAIN_EVENTS).getDeletedCount(); }
    public List<String> expiredPlatformRuns(String job, Instant before, int keep) { return expiredRuns(platform(), platform(), job, before, keep); }
    public long deletePlatformRuns(Collection<String> ids) {
        if (ids.isEmpty()) { return 0; }
        return mongo.remove(Query.query(platform().and("_id").in(ids)), JOB_RUNS).getDeletedCount();
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
    public List<String> expiredRuns(String clubId, String job, Instant before, int keep) { return expiredRuns(scope(clubId), scope(clubId), job, before, keep); }
    /** `owner` twice: a Criteria is mutable, so each of the two queries gets its own instance. */
    private List<String> expiredRuns(Criteria latestOwner, Criteria expiredOwner, String job, Instant before, int keep) {
        var latest = mongo.find(Query.query(latestOwner.and("job").is(job).and("dryRun").ne(true).and("status").ne("SKIPPED"))
                .with(Sort.by(Sort.Direction.DESC, "startedAt", "_id")).limit(keep), Document.class, JOB_RUNS).stream().map(d -> d.getString("_id")).toList();
        return mongo.find(Query.query(expiredOwner.and("job").is(job).and("finishedAt").lt(before).and("_id").nin(latest))
                .with(Sort.by("startedAt", "_id")), Document.class, JOB_RUNS).stream().map(d -> d.getString("_id")).toList();
    }
    public long deleteRuns(String clubId, Collection<String> ids) {
        if (ids.isEmpty()) { return 0; }
        return mongo.remove(Query.query(scope(clubId).and("_id").in(ids)), JOB_RUNS).getDeletedCount();
    }
}
