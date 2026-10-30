package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.MemberMergeParticipant;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Moves operational ownership without changing any historical event or movement. */
@Repository
public class PackMergeRepository extends TenantRepository<PackBalance> implements MemberMergeParticipant {
    public PackMergeRepository(MongoTemplate mongo) { super(mongo, PackBalance.class); }
    private Query selected(String memberId, String[] states) {
        var query = tenantQuery().addCriteria(Criteria.where("memberId").is(memberId));
        if (states != null) query.addCriteria(Criteria.where("state").in((Object[]) states));
        return query;
    }
    @Override public Map<String, Long> preview(String sourceMemberId) {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("packs", mongo.count(selected(sourceMemberId, null), PackBalance.class));
        return counts;
    }
    @Override public java.util.List<String> blockers(String source, String target) {
        return mongo.exists(selected(source, null).addCriteria(Criteria.where("status").in("DUE", "PARTIAL", "PENDING")), UpfrontPayment.class) ? java.util.List.of("MEMBER_PROCESS_PENDING") : java.util.List.of();
    }
    @Override public void merge(String sourceMemberId, String targetMemberId) {
        mongo.updateMulti(selected(sourceMemberId, null), new Update().set("memberId", targetMemberId).inc("version", 1), PackBalance.class);
    }
}
