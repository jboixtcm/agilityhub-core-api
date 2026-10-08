package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class BuildSessionRepository extends CourseTenantRepository<BuildSession> {
    public BuildSessionRepository(MongoTemplate mongo) {
        super(mongo, BuildSession.class);
        mongo.indexOps(BuildSession.class).ensureIndex(new Index().on("clubId", ASC).on("placementId", ASC).named("session_placement"));
        mongo.indexOps(BuildSession.class).ensureIndex(new Index().on("clubId", ASC).on("status", ASC).on("startedAt", ASC).named("session_cleanup"));
        mongo.indexOps(BuildSession.class).ensureIndex(new Index().on("clubId", ASC).on("joinCode", ASC).unique().partial(PartialIndexFilter.of(Criteria.where("status").in("NOT_STARTED", "IN_PROGRESS"))).named("open_join_code"));
    }
    public java.util.Optional<BuildSession> findOpenByCode(String code) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("joinCode").is(code)
                .and("status").in("NOT_STARTED", "IN_PROGRESS")), BuildSession.class));
    }
}
