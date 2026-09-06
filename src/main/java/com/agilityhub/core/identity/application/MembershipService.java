package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Tenant-bound entry point for S03/S05 role and access changes. */
@Service
public class MembershipService {
    private final MembershipRepository memberships;
    private final AccountService accounts;
    private final IdentityTransactions transactions;
    private final EventPublisher events;
    private final Clock clock;
    private final AccountRepository accountRepository;
    private final AccountSessionRepository sessions;
    public MembershipService(MembershipRepository memberships, AccountService accounts, IdentityTransactions transactions, EventPublisher events, Clock clock,
                             AccountRepository accountRepository, AccountSessionRepository sessions) {
        this.memberships = memberships; this.accounts = accounts; this.transactions = transactions; this.events = events; this.clock = clock;
        this.accountRepository = accountRepository; this.sessions = sessions;
    }
    public Membership setRoles(String accountId, Set<Role> roles) {
        return transactions.run(() -> {
            accounts.active(accountId);
            var previous = memberships.findByAccountId(accountId).orElse(null);
            var status = roles.isEmpty() ? Membership.Status.SUSPENDED : previous == null ? Membership.Status.ACTIVE : previous.status();
            return save(accountId, previous, roles, status);
        });
    }
    public Membership suspend(String accountId) { return status(accountId, Membership.Status.SUSPENDED); }
    public Membership resume(String accountId) { return status(accountId, Membership.Status.ACTIVE); }
    private Membership status(String accountId, Membership.Status status) {
        return transactions.run(() -> {
            accounts.active(accountId);
            var old = memberships.findByAccountId(accountId).orElseThrow(() -> new ApiException(ErrorCode.NO_MEMBERSHIP));
            if (old.status() == Membership.Status.ERASED || (status == Membership.Status.ACTIVE && old.roles().isEmpty())) {
                throw new ApiException(ErrorCode.MEMBERSHIP_SUSPENDED);
            }
            return save(accountId, old, old.roles(), status);
        });
    }
    private Membership save(String accountId, Membership old, Set<Role> roles, Membership.Status status) {
        if (old != null && old.status() == Membership.Status.ERASED) { throw new ApiException(ErrorCode.MEMBERSHIP_SUSPENDED); }
        if (old != null && old.roles().equals(roles) && old.status() == status) { return old; }
        accountRepository.touchSessions(accountId);
        Role profile = old != null && old.defaultProfile() != null && roles.contains(old.defaultProfile()) ? old.defaultProfile() : null;
        var next = new Membership(old == null ? UUID.randomUUID().toString() : old.id(), accountId, TenantContext.require(),
                old == null ? null : old.memberId(), roles, status, profile, profile != null && old.rememberProfile(),
                old == null ? null : old.instructorId(), old == null ? clock.instant() : old.createdAt(), old == null ? null : old.lastAccessAt());
        memberships.replace(next);
        if (status == Membership.Status.SUSPENDED) { sessions.revokeClub(accountId, next.clubId(), clock.instant()); }
        events.publish(new IdentityEvent(IdentityEvent.Kind.MembershipChanged, next.clubId(), next.id(), clock.instant(), Map.of(
                "accountId", accountId, "clubId", next.clubId(), "before", old == null ? Set.of() : old.roles(), "after", next.roles(), "status", status.name())));
        return next;
    }
}
