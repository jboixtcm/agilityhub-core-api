package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
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
        mongo.indexOps(Placement.class).createIndex(new Index().on("clubId", ASC).on("ringId", ASC).named("placement_ring"));
        mongo.indexOps(Placement.class).createIndex(new Index().on("clubId", ASC).on("courseId", ASC).named("placement_course"));
        mongo.indexOps(Placement.class).createIndex(new Index().on("clubId", ASC).on("activityId", ASC).named("placement_activity"));
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
    /** S16 §6 `GET /placements`: the club's placements, filtered by each given reference, most recently updated first. */
    public List<Placement> find(String ringId, String courseId, String activityId) {
        var query = tenantQuery();
        if (ringId != null) { query.addCriteria(Criteria.where("ringId").is(ringId)); }
        if (courseId != null) { query.addCriteria(Criteria.where("courseId").is(courseId)); }
        if (activityId != null) { query.addCriteria(Criteria.where("activityId").is(activityId)); }
        return mongo.find(query.with(Sort.by(DESC, "updatedAt").and(Sort.by(ASC, "_id"))), Placement.class);
    }
    /** The ids of the club's placements of a course (R-16-16: a course a visible setup shows through its placement). */
    public List<String> ofCourse(String courseId) {
        return mongo.findDistinct(tenantQuery().addCriteria(Criteria.where("courseId").is(courseId)), "_id", Placement.class, String.class);
    }
    /** R-16-10: the placement's build sheet, at any version (the sheet is not an edit of the placement). */
    public void attachSheet(String id, String fileKey, java.time.Instant now, String actor) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id));
        if (mongo.updateFirst(query, new Update().set("buildSheetFileKey", fileKey).set("updatedAt", now).set("updatedByAccountId", actor), Placement.class)
                .getMatchedCount() != 1) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    /** Optimistic replacement at the stored version; another version is `409 STALE_VERSION`. */
    public void update(Placement next, long expectedVersion) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion));
        if (mongo.findAndReplace(query, next) == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
}
