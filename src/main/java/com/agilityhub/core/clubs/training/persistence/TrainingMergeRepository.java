package com.agilityhub.core.clubs.training.persistence;

import com.agilityhub.core.shared.application.MemberMergeParticipant;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Moves operational ownership without changing any historical event or movement. */
@Repository
public class TrainingMergeRepository extends TenantRepository<TrainingBooking> implements MemberMergeParticipant {
    private final java.time.Clock clock;
    public TrainingMergeRepository(MongoTemplate mongo, java.time.Clock clock) { super(mongo, TrainingBooking.class); this.clock = clock; }
    private Query selected(String memberId, String[] states) {
        var query = tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("endsAt").gt(clock.instant()));
        if (states != null) query.addCriteria(Criteria.where("state").in((Object[]) states));
        return query;
    }
    @Override public Map<String, Long> preview(String sourceMemberId) {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("trainingBookings", mongo.count(selected(sourceMemberId, new String[]{"ACTIVE"}), TrainingBooking.class));
        return counts;
    }
    @Override public void merge(String sourceMemberId, String targetMemberId) {
        mongo.updateMulti(selected(sourceMemberId, new String[]{"ACTIVE"}), new Update().set("memberId", targetMemberId).inc("version", 1), TrainingBooking.class);
    }
}
