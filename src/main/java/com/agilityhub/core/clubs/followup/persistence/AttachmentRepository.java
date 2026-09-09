package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository extends TenantRepository<Attachment> {
    public AttachmentRepository(MongoTemplate mongo) { super(mongo, Attachment.class); }
    public List<Attachment> forEntity(String type, String id) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("entityType").is(type).and("entityId").is(id).and("removedAt").is(null)), Attachment.class);
    }
    public Optional<Attachment> forKey(String key) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("fileKey").is(key)), Attachment.class));
    }
    public void lock(String type, String id) {
        mongo.upsert(Query.query(Criteria.where("_id").is(com.agilityhub.core.shared.application.TenantContext.require() + ":" + type + ":" + id)),
                new Update().setOnInsert("clubId", com.agilityhub.core.shared.application.TenantContext.require()).inc("version", 1), "attachment_write_locks");
    }
    public void ensureIndexes() {
        mongo.indexOps(Attachment.class).ensureIndex(new org.springframework.data.mongodb.core.index.Index()
                .on("clubId", org.springframework.data.domain.Sort.Direction.ASC).on("fileKey", org.springframework.data.domain.Sort.Direction.ASC).unique());
    }
}
