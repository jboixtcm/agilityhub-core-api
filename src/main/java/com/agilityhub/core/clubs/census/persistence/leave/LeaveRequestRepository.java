package com.agilityhub.core.clubs.census.persistence.leave;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** S13 §3 `leave_requests` with its three indexes (E8-T01); E8-T05 adds the service queries. */
@Repository
public class LeaveRequestRepository extends TenantRepository<LeaveRequest> {
    public LeaveRequestRepository(MongoTemplate mongo) { super(mongo, LeaveRequest.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(LeaveRequest.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("state", ASC).named("leave_club_member_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("requestedDate", ASC).named("leave_club_state_requested"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("source", ASC).on("state", ASC).named("leave_club_source_state"));
    }
    /** The request of a member of the open club; another member's, also of the same family group, is empty (R-13-18). */
    public Optional<LeaveRequest> findOwn(String id, String memberId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("memberId").is(memberId)), LeaveRequest.class));
    }
}
