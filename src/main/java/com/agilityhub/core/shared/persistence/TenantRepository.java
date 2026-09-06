package com.agilityhub.core.shared.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Explicit trusted tenant scope until TenantContext is supplied by E0-T05. */
public abstract class TenantRepository<T> {
    protected final MongoTemplate mongo;
    protected TenantRepository(MongoTemplate mongo) { this.mongo = mongo; }
    protected Query tenantQuery(String clubId) {
        return Query.query(Criteria.where("clubId").is(clubId));
    }
}
