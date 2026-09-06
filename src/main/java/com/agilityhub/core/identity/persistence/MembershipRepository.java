package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

@Repository
public class MembershipRepository extends TenantRepository<Membership> {
    public MembershipRepository(MongoTemplate mongo) { super(mongo, Membership.class); }
    public Optional<Membership> findByAccountId(String accountId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("accountId").is(accountId)), Membership.class));
    }
    public void ensureIndexes() {
        mongo.indexOps(Membership.class).ensureIndex(new Index().on("accountId", Direction.ASC)
                .on("clubId", Direction.ASC).unique().named("membership_account_club"));
    }
}
