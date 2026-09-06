package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** All ordinary persistence operations bind the current tenant, including writes by id. */
public abstract class TenantRepository<T extends TenantEntity> {
    protected final MongoTemplate mongo;
    private final Class<T> entityType;
    protected TenantRepository(MongoTemplate mongo, Class<T> entityType) {
        this.mongo = mongo; this.entityType = entityType;
    }
    protected final Query tenantQuery() { return Query.query(Criteria.where("clubId").is(TenantContext.require())); }
    protected final Query tenantQuery(String clubId) {
        if (!TenantContext.require().equals(clubId)) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
        return tenantQuery();
    }
    public Optional<T> findById(String id) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id)), entityType));
    }
    public List<T> findAll() { return mongo.find(tenantQuery(), entityType); }
    public T insert(T entity) { tenantQuery(entity.clubId()); return mongo.insert(entity); }
    public T replace(T entity) {
        Query query = tenantQuery(entity.clubId()).addCriteria(Criteria.where("_id").is(entity.id()));
        // Atomic scoped replacement: another tenant's id fails on the global _id unique index.
        return mongo.findAndReplace(query, entity, FindAndReplaceOptions.options().upsert().returnNew());
    }
    public boolean deleteById(String id) {
        return mongo.remove(tenantQuery().addCriteria(Criteria.where("_id").is(id)), entityType).getDeletedCount() == 1;
    }
}
