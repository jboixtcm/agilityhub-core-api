package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.application.MemberMergeParticipant;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class MembershipMergeRepository extends TenantRepository<Membership> implements MemberMergeParticipant {
    public MembershipMergeRepository(MongoTemplate mongo) { super(mongo, Membership.class); }
    private Query selected(String source) { return tenantQuery().addCriteria(Criteria.where("memberId").is(source)); }
    @Override public Map<String, Long> preview(String source) { return Map.of("accessAccounts", mongo.count(selected(source), Membership.class)); }
    @Override public java.util.List<String> blockers(String source, String target) {
        return mongo.exists(selected(target), Membership.class) && mongo.exists(selected(source), Membership.class) ? java.util.List.of("TWO_MEMBERSHIPS") : java.util.List.of();
    }
    @Override public void merge(String source, String target) {
        if (mongo.exists(selected(target), Membership.class) && mongo.exists(selected(source), Membership.class)) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.INVALID_STATE);
        }
        mongo.updateMulti(selected(source), new Update().set("memberId", target).inc("version", 1), Membership.class);
    }
}
