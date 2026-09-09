package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;

@Repository
public class ParameterRepository extends TenantRepository<Parameter> {
    public ParameterRepository(MongoTemplate mongo) { super(mongo, Parameter.class); }
    public java.util.Optional<Parameter> findByKey(String key, String scopeRef) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(
                org.springframework.data.mongodb.core.query.Criteria.where("key").is(key).and("scopeRef").is(scopeRef)), Parameter.class));
    }
    /** Compare-and-set without upsert: a stale editor must never replace a newer override. */
    public Parameter saveVersioned(Parameter next, Long expectedVersion) {
        try {
            if (expectedVersion == null) {
                tenantQuery(next.clubId());
                // MongoTemplate initializes @Version records to zero on insert; zero is reserved
                // by the API for an absent override, so persist the explicit first version.
                var document = new org.bson.Document();
                mongo.getConverter().write(next, document);
                mongo.insert(document, mongo.getCollectionName(Parameter.class));
                return next;
            }
            var query = tenantQuery(next.clubId()).addCriteria(org.springframework.data.mongodb.core.query.Criteria
                    .where("_id").is(next.id()).and("version").is(expectedVersion));
            var saved = mongo.findAndReplace(query, next,
                    org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
            if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
            return saved;
        } catch (org.springframework.dao.DataAccessException failure) {
            throw SettingsWriteConflict.translate(failure);
        }
    }
    public void ensureIndexes() {
        mongo.indexOps(Parameter.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("key", Direction.ASC)
                .on("scopeRef", Direction.ASC).unique().named("parameter_scope_key"));
    }
}
