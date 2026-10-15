package com.agilityhub.core.courses.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
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
    /** The club's active setups: at most one per ring (`active_setup`). */
    public List<RingSetup> active() { return mongo.find(tenantQuery().addCriteria(Criteria.where("status").is("ACTIVE")), RingSetup.class); }
    /** R-16-07: the ring's active setup, if any. */
    public Optional<RingSetup> activeOn(String ringId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("ringId").is(ringId).and("status").is("ACTIVE")), RingSetup.class));
    }
    /** R-16-04: an active setup of the ring that shows a placement. */
    public boolean activeWithPlacement(String ringId) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("ringId").is(ringId).and("status").is("ACTIVE").and("placementId").ne(null)), RingSetup.class);
    }
    /** R-16-16: whether an active setup of the club shows the course, directly or through one of {@code placementIds}. */
    public boolean showsCourse(String courseId, Collection<String> placementIds) {
        var shows = placementIds.isEmpty() ? Criteria.where("courseId").is(courseId)
                : new Criteria().orOperator(Criteria.where("courseId").is(courseId), Criteria.where("placementId").in(placementIds));
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("status").is("ACTIVE")).addCriteria(shows), RingSetup.class);
    }
    /** R-16-08: one page of the ring's setups, newest first (`setup_history`). */
    public List<RingSetup> history(String ringId, int page, int size) {
        var query = tenantQuery().addCriteria(Criteria.where("ringId").is(ringId)).with(Sort.by(DESC, "builtAt").and(Sort.by(DESC, "_id")));
        return mongo.find(query.skip((long) page * size).limit(size), RingSetup.class);
    }
    public long countOn(String ringId) { return mongo.count(tenantQuery().addCriteria(Criteria.where("ringId").is(ringId)), RingSetup.class); }
    /** S15 R-15-15 step f: the club's active setups whose `expiresAt` is not after {@code now}, oldest expiry first (`setup_expiry`). */
    public List<RingSetup> lapsed(Instant now) {
        var query = tenantQuery().addCriteria(Criteria.where("status").is("ACTIVE").and("expiresAt").lte(now)).with(Sort.by(ASC, "expiresAt").and(Sort.by(ASC, "_id")));
        return mongo.find(query, RingSetup.class);
    }
    /** Replaces a setup that is still `ACTIVE`; false when it is not (closed meanwhile). The whole document goes through the mapping. */
    public boolean replaceActive(RingSetup next) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("status").is("ACTIVE"));
        return mongo.findAndReplace(query, next) != null;
    }
}
