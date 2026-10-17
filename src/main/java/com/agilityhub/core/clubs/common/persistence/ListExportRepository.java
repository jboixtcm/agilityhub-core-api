package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class ListExportRepository extends TenantRepository<ExportJob> {
    public ListExportRepository(MongoTemplate mongo) { super(mongo, ExportJob.class); }
    public void ensureIndexes() {
        mongo.indexOps(ExportJob.class).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("ownerAccountId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("export_owner"));
        mongo.indexOps(ExportJob.class).ensureIndex(new Index().on("status", Sort.Direction.ASC).on("expiresAt", Sort.Direction.ASC).named("export_work"));
        // Only cleaned tombstones get a TTL, so Mongo can never erase the storage-cleanup obligation.
        mongo.indexOps(ExportJob.class).ensureIndex(new Index().on("purgeAt", Sort.Direction.ASC).expire(Duration.ZERO).named("export_purge"));
        mongo.indexOps("export_write_locks").ensureIndex(new Index().on("clubId", Sort.Direction.ASC).unique());
    }
    public void lock() { mongo.upsert(tenantQuery(), new Update().inc("sequence", 1), "export_write_locks"); }
    public List<ExportJob> owned(String account, String kind) {
        var query = tenantQuery().addCriteria(Criteria.where("ownerAccountId").is(account));
        if (kind != null) { query.addCriteria(Criteria.where("kind").is(kind)); }
        return mongo.find(query.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(100), ExportJob.class);
    }
    public long running() { return mongo.count(tenantQuery().addCriteria(Criteria.where("status").is("RUNNING")), ExportJob.class); }
    public ExportJob claim(Instant now, String token) {
        var query = tenantQuery().addCriteria(Criteria.where("kind").in("LIST", "ACCOUNTING", "MEMBER_DATA").and("status").is("QUEUED")
                .orOperator(Criteria.where("expiresAt").gt(now), Criteria.where("expiresAt").is(null)))
                .with(Sort.by("createdAt"));
        return mongo.findAndModify(query, new Update().set("status", "RUNNING").set("claimToken", token)
                .set("leaseUntil", now.plusSeconds(300)).set("progressPct", 0).inc("attempts", 1), FindAndModifyOptions.options().returnNew(true), ExportJob.class);
    }
    public Query fence(ExportJob job, Instant now) {
        return tenantQuery(job.clubId()).addCriteria(Criteria.where("_id").is(job.id()).and("status").is("RUNNING")
                .and("claimToken").is(job.claimToken()).and("leaseUntil").gt(now));
    }
    public boolean updateClaim(ExportJob job, Instant now, Update update) {
        return mongo.updateFirst(fence(job, now), update, ExportJob.class).getModifiedCount() == 1;
    }
    public void registerFile(ExportJob job, Instant now, String key) {
        if (mongo.updateFirst(fence(job, now), new Update().addToSet("fileKeys", key).set("fileKey", key), ExportJob.class).getMatchedCount() != 1) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.INVALID_STATE);
        }
    }
    public void recover(Instant now) {
        for (var job : mongo.find(tenantQuery().addCriteria(Criteria.where("status").is("RUNNING").and("leaseUntil").lte(now)), ExportJob.class)) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(job.id()).and("claimToken").is(job.claimToken()).and("leaseUntil").lte(now)),
                    new Update().set("status", job.attempts() >= 3 ? "FAILED" : "QUEUED").set("errorCode", "INVALID_STATE")
                            .unset("claimToken").unset("leaseUntil"), ExportJob.class);
        }
    }
    public List<ExportJob> expired(Instant now) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("status").ne("EXPIRED").and("expiresAt").lte(now)).limit(100), ExportJob.class);
    }
    /** S15 P9: finished (READY/FAILED) jobs created before `before` that still keep their tombstone-less record. */
    public List<ExportJob> purgeable(Instant before) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("status").in("READY", "FAILED").and("createdAt").lt(before))
                .with(Sort.by("createdAt", "_id")), ExportJob.class);
    }
    /** S15 P9 «registre marcat PURGED»: the existing tombstone (EXPIRED, files unset) with an immediate `purgeAt`. */
    public boolean purged(ExportJob job, Instant now) {
        return mongo.updateFirst(tenantQuery(job.clubId()).addCriteria(Criteria.where("_id").is(job.id()).and("status").in("READY", "FAILED")),
                new Update().set("status", "EXPIRED").set("purgeAt", now).unset("fileKey").unset("fileKeys").unset("claimToken").unset("leaseUntil"),
                ExportJob.class).getModifiedCount() == 1;
    }
    public void cleaned(ExportJob job, Instant now) {
        mongo.updateFirst(tenantQuery(job.clubId()).addCriteria(Criteria.where("_id").is(job.id()).and("expiresAt").lte(now)),
                new Update().set("status", "EXPIRED").set("purgeAt", now.plus(Duration.ofDays(7)))
                        .unset("fileKey").unset("fileKeys").unset("claimToken").unset("leaseUntil"), ExportJob.class);
    }

    /**
     * S14 R-14-15: the club's export jobs owned by erased accounts or packaging an erased member's data — tombstoned at once
     * (`EXPIRED`, files unset, `purgeAt` now); returns their file keys for the cleanup.
     */
    public List<String> eraseFor(Collection<String> ownerAccountIds, Collection<String> subjectMemberIds, Instant now) {
        var query = tenantQuery().addCriteria(new Criteria().orOperator(Criteria.where("ownerAccountId").in(ownerAccountIds),
                Criteria.where("subjectMemberId").in(subjectMemberIds)));
        var keys = new ArrayList<String>();
        for (var job : mongo.find(query, ExportJob.class)) {
            if (job.fileKeys() != null) { keys.addAll(job.fileKeys()); }
            if (job.fileKey() != null && !keys.contains(job.fileKey())) { keys.add(job.fileKey()); }
        }
        mongo.updateMulti(query, new Update().set("status", "EXPIRED").set("purgeAt", now).unset("fileKey").unset("fileKeys")
                .unset("claimToken").unset("leaseUntil").unset("query"), ExportJob.class);
        return keys;
    }
    /** S14 R-14-13: the member's last package request since `since` (one per 24 h, `DATA_EXPORT_TOO_SOON`). */
    public boolean memberDataSince(String memberId, Instant since) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("kind").is("MEMBER_DATA").and("subjectMemberId").is(memberId)
                .and("createdAt").gt(since)), ExportJob.class);
    }
}
