package com.agilityhub.core.platform.persistence.audit;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Append-only facade. TenantRepository mutation helpers are not exposed to callers. */
@Repository
public class AuditRepository {
    private final MongoTemplate mongo;
    private final TenantEntries tenants;

    public AuditRepository(MongoTemplate mongo) {
        this.mongo = mongo;
        this.tenants = new TenantEntries(mongo);
    }

    public void append(AuditEntry entry) {
        if (entry.clubId() == null) {
            requirePlatformScope();
            mongo.insert(entry);
        } else {
            tenants.insert(entry);
        }
    }

    public Optional<AuditEntry> lastChange(String entityType, String entityId) {
        return tenants.lastChange(entityType, entityId);
    }

    /** Explicit global operation selects only platform entries, never all tenants. */
    public Optional<AuditEntry> lastPlatformChange(String entityType, String entityId) {
        requirePlatformScope();
        return findLast(Query.query(Criteria.where("clubId").is(null)), entityType, entityId);
    }

    private void requirePlatformScope() {
        if (TenantContext.current() != null) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.TENANT_MISMATCH);
        }
    }

    private Optional<AuditEntry> findLast(Query query, String entityType, String entityId) {
        return Optional.ofNullable(mongo.findOne(query.addCriteria(Criteria.where("entityType").is(entityType)
                .and("entityId").is(entityId)).with(Sort.by(Sort.Direction.DESC, "at", "_id")).limit(1), AuditEntry.class));
    }

    public void ensureIndexes() {
        var indexes = mongo.indexOps(AuditEntry.class);
        indexes.ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("at", Sort.Direction.DESC).named("audit_club_at"));
        indexes.ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("memberId", Sort.Direction.ASC)
                .on("at", Sort.Direction.DESC).named("audit_club_member_at"));
        indexes.ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("impersonatedMemberId", Sort.Direction.ASC)
                .on("at", Sort.Direction.DESC).named("audit_club_impersonated_at"));
        indexes.ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("entityType", Sort.Direction.ASC)
                .on("entityId", Sort.Direction.ASC).on("at", Sort.Direction.DESC).named("audit_club_entity_at"));
    }

    private class TenantEntries extends TenantRepository<AuditEntry> {
        TenantEntries(MongoTemplate mongo) { super(mongo, AuditEntry.class); }
        Optional<AuditEntry> lastChange(String entityType, String entityId) {
            return findLast(tenantQuery(), entityType, entityId);
        }
    }
}
