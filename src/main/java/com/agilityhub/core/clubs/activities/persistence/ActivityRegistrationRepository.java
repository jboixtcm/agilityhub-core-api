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
    public ActivityRegistration require(String id) { return findById(id).orElseThrow(() -> new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND)); }
    public ActivityRegistration update(ActivityRegistration next, long expected) {
        org.bson.Document data = new org.bson.Document(); mongo.getConverter().write(next, data);
        Update update = new Update();
        data.forEach((key, value) -> { if (!java.util.Set.of("_id", "_class", "clubId").contains(key)) update.set(key,value); });
        for (var field : ActivityRegistration.class.getRecordComponents()) if (!data.containsKey(field.getName()) && !java.util.Set.of("id", "clubId").contains(field.getName())) update.unset(field.getName());
        if (mongo.updateFirst(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expected)),update,org.bson.Document.class,"activity_registrations").getMatchedCount()!=1)
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION);
        return next;
    }
    public java.util.List<ActivityRegistration> forActivity(String id) { return mongo.find(tenantQuery().addCriteria(Criteria.where("activityId").is(id)),ActivityRegistration.class); }
    public java.util.List<ActivityRegistration> forMember(String id) { return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(id)),ActivityRegistration.class); }
    /** The activity's WAITLISTED registrations, its active waiting entries (E5-T20 `waitlistRank`); served by `registration_club_activity_state_position`. */
    public java.util.List<ActivityRegistration> waiting(String id) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("activityId").is(id).and("state").is(com.agilityhub.core.clubs.activities.domain.RegistrationState.WAITLISTED)),ActivityRegistration.class);
    }
    public java.util.List<ActivityRegistration> live(String id) { return forActivity(id).stream().filter(r -> r.state()!=com.agilityhub.core.clubs.activities.domain.RegistrationState.CANCELLED).toList(); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(ActivityRegistration.class).ensureIndex(new Index().on("clubId", ASC).on("activityId", ASC).on("memberId", ASC).unique().partial(PartialIndexFilter.of(Criteria.where("state").in("ACTIVE", "WAITLISTED"))).named("registration_club_activity_member_live"));
        mongo.indexOps(ActivityRegistration.class).ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("activityStartsAt", ASC).on("state", ASC).named("registration_club_member_start_state"));
        mongo.indexOps(ActivityRegistration.class).ensureIndex(new Index().on("clubId", ASC).on("activityId", ASC).on("state", ASC).on("position", ASC).named("registration_club_activity_state_position"));
    }
}
