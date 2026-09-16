package com.agilityhub.core.clubs.activities.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class ActivityRegistrationRepository extends TenantRepository<ActivityRegistration> {
    public ActivityRegistrationRepository(MongoTemplate mongo) { super(mongo, ActivityRegistration.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(ActivityRegistration.class).ensureIndex(new Index().on("clubId", ASC).on("activityId", ASC).on("memberId", ASC).unique().partial(PartialIndexFilter.of(Criteria.where("state").in("ACTIVE", "WAITLISTED"))).named("registration_club_activity_member_live"));
        mongo.indexOps(ActivityRegistration.class).ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("activityStartsAt", ASC).on("state", ASC).named("registration_club_member_start_state"));
        mongo.indexOps(ActivityRegistration.class).ensureIndex(new Index().on("clubId", ASC).on("activityId", ASC).on("state", ASC).on("position", ASC).named("registration_club_activity_state_position"));
    }
}
