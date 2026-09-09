package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class MigrationRunRepository extends TenantRepository<MigrationRun> {
    public MigrationRunRepository(MongoTemplate mongo) { super(mongo,MigrationRun.class); }
    public void lock(boolean production) {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())),new Update().inc("sequence",1),"migration_write_locks");
        if (production && mongo.exists(tenantQuery().addCriteria(Criteria.where("env").is("PRODUCTION").and("status").is("COMPLETED")),MigrationRun.class)) {
            throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED);
        }
    }
}
