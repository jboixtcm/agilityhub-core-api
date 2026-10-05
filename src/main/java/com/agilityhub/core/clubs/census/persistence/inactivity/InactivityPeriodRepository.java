package com.agilityhub.core.clubs.census.persistence.inactivity;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** S13 §3 `inactivity_periods` with its two indexes (E8-T01); E8-T05 adds the service queries. */
@Repository
public class InactivityPeriodRepository extends TenantRepository<InactivityPeriod> {
    public InactivityPeriodRepository(MongoTemplate mongo) { super(mongo, InactivityPeriod.class); }
    public java.util.List<InactivityPeriod> ofMember(String memberId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId)), InactivityPeriod.class);
    }
    public java.util.List<InactivityPeriod> inStates(String... states) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").in((Object[]) states)), InactivityPeriod.class);
    }
    public InactivityPeriod save(InactivityPeriod value, Long expected) {
        var saved = mongo.findAndReplace(tenantQuery(value.clubId()).addCriteria(Criteria.where("_id").is(value.id()).and("version").is(expected)),
                value, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return saved;
    }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(InactivityPeriod.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("state", ASC).named("inactivity_club_member_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("fromMonth", ASC).named("inactivity_club_state_from"));
    }
    /** The period of a member of the open club; another member's, also of the same family group, is empty (R-13-18). */
    public Optional<InactivityPeriod> findOwn(String id, String memberId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("memberId").is(memberId)), InactivityPeriod.class));
    }
}
