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
    public Optional<Membership> findByMemberId(String memberId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId)), Membership.class));
    }
    public long activeAdmins() {
        return mongo.count(tenantQuery().addCriteria(Criteria.where("roles").is("ADMIN").and("status").is("ACTIVE")), Membership.class);
    }
    public void saveTeam(Membership next) {
        var update = new org.springframework.data.mongodb.core.query.Update()
                .set("roles", next.roles()).set("status", next.status()).set("defaultProfile", next.defaultProfile())
                .set("rememberProfile", next.rememberProfile()).set("instructorId", next.instructorId())
                .set("adminProfile", next.adminProfile()).set("version", next.version()).set("updatedAt", next.updatedAt())
                .set("updatedByAccountId", next.updatedByAccountId());
        mongo.updateFirst(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id())), update, Membership.class);
    }
    public void profile(String accountId, com.agilityhub.core.identity.domain.Role profile, boolean remember) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("accountId").is(accountId)),
                new org.springframework.data.mongodb.core.query.Update().set("defaultProfile", remember ? profile : null)
                        .set("rememberProfile", remember), Membership.class);
    }
    public void accessed(String accountId, java.time.Instant now) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("accountId").is(accountId)),
                new org.springframework.data.mongodb.core.query.Update().set("lastAccessAt", now), Membership.class);
    }
    public void ensureIndexes() {
        mongo.indexOps(Membership.class).ensureIndex(new Index().on("accountId", Direction.ASC)
                .on("clubId", Direction.ASC).unique().named("membership_account_club"));
    }
}
