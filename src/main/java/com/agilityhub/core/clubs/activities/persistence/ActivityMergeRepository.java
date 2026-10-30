package com.agilityhub.core.clubs.activities.persistence;

import com.agilityhub.core.shared.application.MemberMergeParticipant;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityMergeRepository extends TenantRepository<ActivityRegistration> implements MemberMergeParticipant {
    private final Clock clock;
    public ActivityMergeRepository(MongoTemplate mongo, Clock clock) { super(mongo, ActivityRegistration.class); this.clock = clock; }
    private Query selected(String memberId) {
        var ongoing = mongo.find(tenantQuery().addCriteria(Criteria.where("endsAt").gt(clock.instant()).and("startsAt").lte(clock.instant())), Activity.class).stream().map(Activity::id).toList();
        return tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("state").in("ACTIVE", "WAITLISTED"))
                .addCriteria(new Criteria().orOperator(Criteria.where("activityStartsAt").gte(clock.instant()), Criteria.where("activityId").in(ongoing)));
    }
    @Override public Map<String,Long> preview(String source) { return Map.of("activityRegistrations", mongo.count(selected(source), ActivityRegistration.class)); }
    @Override public List<String> blockers(String source, String target) {
        var activities = mongo.find(selected(source), ActivityRegistration.class).stream().map(ActivityRegistration::activityId).toList();
        return !activities.isEmpty() && mongo.exists(selected(target).addCriteria(Criteria.where("activityId").in(activities)), ActivityRegistration.class) ? List.of("ACTIVITY_CONFLICT") : List.of();
    }
    @Override public void merge(String source, String target) {
        if (!blockers(source, target).isEmpty()) throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.INVALID_STATE);
        mongo.updateMulti(selected(source), new Update().set("memberId", target).inc("version", 1), ActivityRegistration.class);
    }
}
