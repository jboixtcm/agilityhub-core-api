package com.agilityhub.core.clubs.activities.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class ActivityRepository extends TenantRepository<Activity> {
    public ActivityRepository(MongoTemplate mongo) { super(mongo, Activity.class); }
    public java.util.Optional<Activity> findBySlug(String slug) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("slug").is(slug)), Activity.class));
    }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(Activity.class).ensureIndex(new Index().on("clubId", ASC).on("slug", ASC).unique().named("activity_club_slug"));
        mongo.indexOps(Activity.class).ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("startsAt", ASC).named("activity_club_state_start"));
        mongo.indexOps(Activity.class).ensureIndex(new Index().on("clubId", ASC).on("date", ASC).named("activity_club_date"));
    }
}
