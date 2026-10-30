package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.application.MemberMergeParticipant;
import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriod;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequest;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class MemberMergeLifecycleRepository extends TenantRepository<Member> implements MemberMergeParticipant {
    public MemberMergeLifecycleRepository(MongoTemplate mongo) { super(mongo, Member.class); }
    private Query selected(String source) { return tenantQuery().addCriteria(Criteria.where("memberId").in(source)); }
    @Override public Map<String,Long> preview(String source) { return Map.of(); }
    @Override public List<String> blockers(String source, String target) {
        boolean pending = mongo.exists(selected(source).addCriteria(Criteria.where("state").in("PENDING", "APPROVED").and("executedAt").is(null)), LeaveRequest.class)
                || mongo.exists(selected(source).addCriteria(Criteria.where("state").in("REQUESTED", "APPROVED", "ACTIVE")), InactivityPeriod.class);
        boolean targetPending = mongo.exists(selected(target).addCriteria(Criteria.where("state").in("PENDING", "APPROVED").and("executedAt").is(null)), LeaveRequest.class)
                || mongo.exists(selected(target).addCriteria(Criteria.where("state").in("REQUESTED", "APPROVED", "ACTIVE")), InactivityPeriod.class);
        return pending || targetPending ? List.of("MEMBER_PROCESS_PENDING") : List.of();
    }
    @Override public void merge(String source, String target) { }
}
