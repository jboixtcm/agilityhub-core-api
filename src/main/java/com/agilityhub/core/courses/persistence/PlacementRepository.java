package com.agilityhub.core.courses.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
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
    /** R-16-06: every placement of the ring keeps its `resolvedObstacles` and becomes `stale` until the client recalculates it. */
    public long markStale(String ringId) {
        return mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("ringId").is(ringId).and("stale").ne(true)),
                new Update().set("stale", true), Placement.class).getModifiedCount();
    }
    /** R-16-04: a placement that is not archived still uses the ring's geometry. */
    public boolean usesRing(String ringId) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("ringId").is(ringId).and("status").ne("ARCHIVED")), Placement.class);
    }
    /** The course of each named placement of the club, by placement id. */
    public Map<String, String> courses(Collection<String> ids) {
        var result = new HashMap<String, String>();
        if (ids.isEmpty()) { return result; }
        var query = tenantQuery().addCriteria(Criteria.where("_id").in(ids));
        mongo.find(query, Placement.class).forEach(placement -> result.put(placement.id(), placement.courseId()));
        return result;
    }
}
