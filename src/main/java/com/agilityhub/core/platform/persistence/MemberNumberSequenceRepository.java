package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;

/** Kept outside the replaceable club configuration so applying a seed never reuses a number. */
public final class MemberNumberSequenceRepository extends TenantRepository<MemberNumberSequenceRepository.Sequence> {
    @Document("member_number_sequences")
    public record Sequence(@Id String id, String clubId, long next) implements TenantEntity { }
    public MemberNumberSequenceRepository(MongoTemplate mongo) { super(mongo, Sequence.class); }
    public int next(long minimum) {
        var query=tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require()));
        mongo.upsert(query,new Update().max("next",Math.max(1,minimum)),Sequence.class);
        var before=mongo.findAndModify(query,new Update().inc("next",1L),Sequence.class);
        return Math.toIntExact(before.next());
    }
}
