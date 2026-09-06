package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Privileged account-owned projection for the global OIDC memberships scope. No unfiltered list operation. */
@Repository
public class AccountMembershipRepository extends GlobalRepository<Membership> {
    public AccountMembershipRepository(MongoTemplate mongo) { super(mongo, Membership.class); }
    public List<Membership> activeForAccount(String authenticatedAccountId) {
        return mongo.find(Query.query(Criteria.where("accountId").is(authenticatedAccountId).and("status").is(Membership.Status.ACTIVE)), Membership.class);
    }
}
