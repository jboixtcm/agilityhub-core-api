package com.agilityhub.core.platform.persistence.jobs;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobActionMarkRepository extends TenantRepository<JobActionMark> {
    public JobActionMarkRepository(MongoTemplate mongo) { super(mongo, JobActionMark.class); }
}
