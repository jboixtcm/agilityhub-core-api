package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class RingSetupRepository extends CourseTenantRepository<RingSetup> {
    public RingSetupRepository(MongoTemplate mongo) {
        super(mongo, RingSetup.class);
        mongo.indexOps(RingSetup.class).ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).unique().partial(PartialIndexFilter.of(Criteria.where("status").is("ACTIVE"))).named("active_setup"));
        mongo.indexOps(RingSetup.class).ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("builtAt", DESC).named("setup_history"));
        mongo.indexOps(RingSetup.class).ensureIndex(new Index().on("clubId", ASC).on("status", ASC).on("expiresAt", ASC).named("setup_expiry"));
        mongo.indexOps(RingSetup.class).ensureIndex(new Index().on("clubId", ASC).on("courseId", ASC).on("status", ASC).named("setup_course"));
        mongo.indexOps(RingSetup.class).ensureIndex(new Index().on("clubId", ASC).on("placementId", ASC).on("status", ASC).named("setup_placement"));
    }
}
