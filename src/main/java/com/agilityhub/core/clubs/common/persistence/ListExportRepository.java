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
        var query = tenantQuery().addCriteria(Criteria.where("kind").is("LIST").and("status").is("QUEUED")
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
    public void cleaned(ExportJob job, Instant now) {
        mongo.updateFirst(tenantQuery(job.clubId()).addCriteria(Criteria.where("_id").is(job.id()).and("expiresAt").lte(now)),
                new Update().set("status", "EXPIRED").set("purgeAt", now.plus(Duration.ofDays(7)))
                        .unset("fileKey").unset("fileKeys").unset("claimToken").unset("leaseUntil"), ExportJob.class);
    }
}
