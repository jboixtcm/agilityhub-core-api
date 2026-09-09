package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.util.List;
import java.time.Instant;
import org.springframework.data.mongodb.core.query.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/** Privileged tenant discovery and aggregate account quotas; job mutations use TenantRepository. */
@Repository
public class ExportWorkRepository extends GlobalRepository<ExportJob> {
    public ExportWorkRepository(MongoTemplate mongo) { super(mongo, ExportJob.class); }
    public void ensureIndexes() {
        mongo.indexOps("export_account_locks").ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("accountId", org.springframework.data.domain.Sort.Direction.ASC).unique());
        mongo.indexOps("export_jobs").ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("ownerAccountId", org.springframework.data.domain.Sort.Direction.ASC).on("createdAt", org.springframework.data.domain.Sort.Direction.DESC));
    }
    public void lockAccount(String account) { mongo.upsert(Query.query(Criteria.where("accountId").is(account)), new Update().inc("sequence", 1), "export_account_locks"); }
    public long recent(String account, Instant since) {
        return mongo.count(Query.query(Criteria.where("ownerAccountId").is(account).and("createdAt").gt(since)), ExportJob.class);
    }
    public List<String> clubs(Instant now) {
        return mongo.getCollection("export_jobs").distinct("clubId", new Document("$or", List.of(new Document("status", new Document("$in", List.of("QUEUED", "RUNNING"))),
                        new Document("status", new Document("$ne", "EXPIRED")).append("expiresAt", new Document("$lte", java.util.Date.from(now))))), String.class)
                .into(new java.util.ArrayList<>());
    }
}
