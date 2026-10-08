package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class PlacementRepository extends CourseTenantRepository<Placement> {
    public PlacementRepository(MongoTemplate mongo) {
        super(mongo, Placement.class);
        mongo.indexOps(Placement.class).ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).named("placement_ring"));
        mongo.indexOps(Placement.class).ensureIndex(new Index().on("clubId", ASC).on("courseId", ASC).named("placement_course"));
        mongo.indexOps(Placement.class).ensureIndex(new Index().on("clubId", ASC).on("activityId", ASC).named("placement_activity"));
    }
}
