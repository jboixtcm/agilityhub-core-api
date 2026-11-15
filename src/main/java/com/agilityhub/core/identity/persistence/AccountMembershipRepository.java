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
    /**
     * AUTH-01 (S01 R-01-05): whether the account holds a staff role (`ADMIN` or `INSTRUCTOR`) in any club, in any membership status but
     * `ERASED`; a yes or no, never the clubs.
     */
    public boolean holdsStaffRole(String accountId) {
        return mongo.exists(Query.query(Criteria.where("accountId").is(accountId).and("status").ne(Membership.Status.ERASED)
                .and("roles").in(com.agilityhub.core.identity.domain.Role.ADMIN, com.agilityhub.core.identity.domain.Role.INSTRUCTOR)), Membership.class);
    }
    /** R-01-14 (INC-37): how many clubs the account still belongs to, in any status but `ERASED`; a count, never the clubs. */
    public long countNotErased(String accountId) {
        return mongo.count(Query.query(Criteria.where("accountId").is(accountId).and("status").ne(Membership.Status.ERASED)), Membership.class);
    }
}
