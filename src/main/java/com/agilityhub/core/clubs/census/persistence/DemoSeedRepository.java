package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DemoSeedRepository extends TenantRepository<DemoSeedRun> {
    public DemoSeedRepository(MongoTemplate mongo) { super(mongo, DemoSeedRun.class); }
}
