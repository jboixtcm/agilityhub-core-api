package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;

@Repository
public class ParameterRepository extends TenantRepository<Parameter> {
    public ParameterRepository(MongoTemplate mongo) { super(mongo, Parameter.class); }
    public void ensureIndexes() {
        mongo.indexOps(Parameter.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("key", Direction.ASC)
                .on("scopeRef", Direction.ASC).unique().named("parameter_scope_key"));
    }
}
