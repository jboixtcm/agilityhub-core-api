package com.agilityhub.core.platform.application.privacy;

import java.util.List;
import java.util.Optional;

/** S14 R-14-14/R-14-15: the identity side of the erasure (implemented by `identity`); global, no tenant. */
public interface ErasureAccounts {
    Optional<Account> account(String accountId);
    List<Membership> memberships(String accountId);

    /** `federated` = linked to Learn (`externalIds.learnUserId`): its account is never erased with `scope = MEMBER` (S14 §13-7). */
    record Account(String id, String email, String locale, boolean federated, boolean erased) { }
    record Membership(String clubId, String memberId, String status) {
        public boolean active() { return "ACTIVE".equals(status); }
    }
}
