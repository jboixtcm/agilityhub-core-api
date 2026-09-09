package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.domain.CatalogEntity;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.bson.Document;

/** Compare-and-set partial writes preserve fields owned by other verticals (notably ring geometry). */
public class CatalogRepository<T extends CatalogEntity> extends TenantRepository<T> {
    private final Class<T> type;
    public CatalogRepository(MongoTemplate mongo, Class<T> type) { super(mongo, type); this.type = type;
        mongo.indexOps("catalog_write_locks").ensureIndex(new org.springframework.data.mongodb.core.index.Index()
                .on("clubId", org.springframework.data.domain.Sort.Direction.ASC).on("catalog", org.springframework.data.domain.Sort.Direction.ASC).unique());
    }
    public void lock() {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("catalog").is(type.getSimpleName())),
                new Update().inc("sequence", 1), "catalog_write_locks");
    }
    private Document document(T entity) {
        Document result = new Document();
        mongo.getConverter().write(entity, result);
        if (entity instanceof Level level) {
            result.put("nameKeys", level.name().values().entrySet().stream()
                    .map(entry -> entry.getKey() + ":" + entry.getValue().toLowerCase(java.util.Locale.ROOT)).toList());
        }
        return result;
    }
    @Override public T insert(T entity) {
        tenantQuery(entity.clubId());
        mongo.insert(document(entity), mongo.getCollectionName(type));
        return entity;
    }
    public T update(T next, long expectedVersion) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion));
        Document document = document(next);
        Update update = new Update();
        document.forEach((key, value) -> { if (!key.equals("_id") && !key.equals("_class")) { update.set(key, value); } });
        // Null resets must be written even when Mongo's converter omits null properties.
        if (next instanceof Ring ring && ring.trainingCapacity() == null) { update.unset("trainingCapacity"); }
        if (mongo.updateFirst(query, update, type).getMatchedCount() != 1) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return next;
    }
    public void delete(T entity) {
        if (mongo.remove(tenantQuery(entity.clubId()).addCriteria(Criteria.where("_id").is(entity.id()).and("version").is(entity.version())), type)
                .getDeletedCount() != 1) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
}
