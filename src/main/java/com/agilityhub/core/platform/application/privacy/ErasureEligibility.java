package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S14 R-14-14 `ErasureEligibility`: a member must be `LEFT` (`MEMBER_NOT_LEFT`), and no context may still hold something open
 * for it (`INVOICES_OPEN`, `UPFRONT_OPEN`, `FUTURE_BOOKINGS`); an account's request also needs every membership inactive
 * (`OTHER_MEMBERSHIPS_ACTIVE`). The same checks run again when the request executes (R-14-15).
 */
@Service
public class ErasureEligibility {
    private final List<ErasureCheck> checks;
    private final ErasureAccounts accounts;
    public ErasureEligibility(List<ErasureCheck> checks, ErasureAccounts accounts) { this.checks = List.copyOf(checks); this.accounts = accounts; }

    /** The members of the current club; an already erased member blocks nothing. */
    public Set<ErasureBlockReason> check(Collection<ErasureMember> members, Instant now) {
        var reasons = EnumSet.noneOf(ErasureBlockReason.class);
        var pending = members.stream().filter(member -> !member.erased()).toList();
        if (pending.stream().anyMatch(member -> !member.left())) { reasons.add(ErasureBlockReason.MEMBER_NOT_LEFT); }
        var ids = pending.stream().map(ErasureMember::id).toList();
        if (!ids.isEmpty()) { checks.forEach(check -> reasons.addAll(check.blockReasons(ids, now))); }
        return reasons;
    }

    /** `scope = ACCOUNT`: any membership still `ACTIVE` (a role kept in a club, or a member not yet left there). */
    public boolean otherMembershipsActive(String accountId) {
        return accountId != null && accounts.memberships(accountId).stream().anyMatch(ErasureAccounts.Membership::active);
    }
}
