package com.agilityhub.core.platform.persistence.audit;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Append-only facade. TenantRepository mutation helpers are not exposed to callers. The two documented exceptions are S14's:
 * the erasure's pseudonymisation of a member's or an erased account's entries (R-14-15) and the retention purge (R-14-11, R-14-16 d).
 */
@Repository
public class AuditRepository {
    private final MongoTemplate mongo;
    private final TenantEntries tenants;
    private final com.agilityhub.core.shared.application.TenantWriteFence writes;

    public AuditRepository(MongoTemplate mongo, com.agilityhub.core.shared.application.TenantWriteFence writes) {
        this.mongo = mongo; this.writes = writes;
        this.tenants = new TenantEntries(mongo);
    }

    public void append(AuditEntry entry) {
        if (entry.clubId() == null) {
            requirePlatformScope();
            mongo.insert(entry);
        } else {
            writes.written(entry.clubId());
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

    /** S14 R-14-15: the fields an erased entry loses; action, entity, ids and date stay. */
    private static Update erasedFields() {
        return new Update().set("changes", java.util.List.of()).unset("reason").unset("actorName").unset("impersonatedName")
                .unset("entityLabel").unset("ip").unset("userAgent").unset("details");
    }
    /** The current club's entries about these members (`memberId`, `impersonatedMemberId`) or acted by these erased accounts. */
    public long eraseMembers(Collection<String> memberIds, Collection<String> erasedAccountIds) {
        var who = new java.util.ArrayList<Criteria>();
        if (!memberIds.isEmpty()) { who.add(Criteria.where("memberId").in(memberIds)); who.add(Criteria.where("impersonatedMemberId").in(memberIds)); }
        if (!erasedAccountIds.isEmpty()) { who.add(Criteria.where("actorAccountId").in(erasedAccountIds)); }
        if (who.isEmpty()) { return 0; }
        writes.written(TenantContext.require());
        var query = Query.query(Criteria.where("clubId").is(TenantContext.require()).orOperator(who.toArray(Criteria[]::new)));
        return mongo.updateMulti(query, erasedFields(), AuditEntry.class).getModifiedCount();
    }
    /** An erased account's own entries in every club and on the platform (R-14-15, the global part). */
    public long eraseActor(String accountId) {
        return mongo.updateMulti(Query.query(Criteria.where("actorAccountId").is(accountId)), erasedFields(), AuditEntry.class).getModifiedCount();
    }
    /** R-14-11 / R-14-16 (d): entries of the current club older than the cut-off; the purge deletes them. */
    public long countBefore(Instant cutoff) {
        return mongo.count(Query.query(Criteria.where("clubId").is(TenantContext.require()).and("at").lt(cutoff)), AuditEntry.class);
    }
    public long purgeBefore(Instant cutoff) {
        writes.written(TenantContext.require());
        return mongo.remove(Query.query(Criteria.where("clubId").is(TenantContext.require()).and("at").lt(cutoff)), AuditEntry.class).getDeletedCount();
    }

    /** S14 R-14-13: the current club's entries about the member, oldest first. */
    public java.util.List<AuditEntry> aboutMember(String memberId) {
        return mongo.find(Query.query(Criteria.where("clubId").is(TenantContext.require()).orOperator(Criteria.where("memberId").is(memberId),
                Criteria.where("impersonatedMemberId").is(memberId))).with(Sort.by("at", "_id")), AuditEntry.class);
    }

    public void ensureIndexes() {
        var indexes = mongo.indexOps(AuditEntry.class);
        indexes.createIndex(new Index().on("clubId", Sort.Direction.ASC).on("at", Sort.Direction.DESC).named("audit_club_at"));
        indexes.createIndex(new Index().on("clubId", Sort.Direction.ASC).on("memberId", Sort.Direction.ASC)
                .on("at", Sort.Direction.DESC).named("audit_club_member_at"));
        indexes.createIndex(new Index().on("clubId", Sort.Direction.ASC).on("impersonatedMemberId", Sort.Direction.ASC)
                .on("at", Sort.Direction.DESC).named("audit_club_impersonated_at"));
        indexes.createIndex(new Index().on("clubId", Sort.Direction.ASC).on("entityType", Sort.Direction.ASC)
                .on("entityId", Sort.Direction.ASC).on("at", Sort.Direction.DESC).named("audit_club_entity_at"));
    }

    private class TenantEntries extends TenantRepository<AuditEntry> {
        TenantEntries(MongoTemplate mongo) { super(mongo, AuditEntry.class); }
        Optional<AuditEntry> lastChange(String entityType, String entityId) {
            return findLast(tenantQuery(), entityType, entityId);
        }
    }
}
