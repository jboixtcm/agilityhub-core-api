package com.agilityhub.core.shared.persistence;

import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Global aggregates and explicitly privileged infrastructure only. */
public abstract class GlobalRepository<T> {
    protected final MongoTemplate mongo;
    private final Class<T> entityType;
    protected GlobalRepository(MongoTemplate mongo, Class<T> entityType) {
        this.mongo = mongo; this.entityType = entityType;
    }
    public Optional<T> findById(String id) { return Optional.ofNullable(mongo.findById(id, entityType)); }
    public T save(T entity) { return mongo.save(entity); }
}
