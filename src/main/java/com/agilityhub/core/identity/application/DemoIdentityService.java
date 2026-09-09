package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.*;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal seed boundary; existing credentials/roles are preserved and accounts cannot be relinked. */
@Service
public class DemoIdentityService {
    private final AccountService service; private final AccountRepository accounts; private final MembershipRepository memberships;
    private final EventPublisher events; private final Clock clock;
    public DemoIdentityService(AccountService service, AccountRepository accounts, MembershipRepository memberships, EventPublisher events, Clock clock) {
        this.service = service; this.accounts = accounts; this.memberships = memberships; this.events = events; this.clock = clock;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public String link(String memberId, String email, String name, String locale, boolean active) {
        if (!email.matches("[^@\\s]+@example\\.test")) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var existing = accounts.findByEmail(email).orElse(null);
        if (existing != null && existing.status() != Account.Status.ACTIVE) { throw new ApiException(ErrorCode.INVALID_STATE); }
        var account = service.getOrCreate(email, name, locale, Account.Source.CONSOLE, null, false);
        var old = memberships.findByAccountId(account.id()).orElse(null);
        if (old != null && (old.memberId() != null || old.status() != Membership.Status.ACTIVE)) { throw new ApiException(ErrorCode.CLUB_NOT_EMPTY); }
        var roles = old == null ? Set.of(Role.MEMBER) : old.roles();
        var next = new Membership(old == null ? UUID.randomUUID().toString() : old.id(), account.id(), TenantContext.require(), memberId,
                roles, active ? Membership.Status.ACTIVE : Membership.Status.SUSPENDED, old == null ? Role.MEMBER : old.defaultProfile(),
                old != null && old.rememberProfile(), old == null ? null : old.instructorId(), old == null ? clock.instant() : old.createdAt(),
                old == null ? null : old.lastAccessAt(), old == null ? null : old.adminProfile(), old == null ? 0 : old.version() + 1,
                clock.instant(), null, null);
        memberships.replace(next);
        events.publish(new TeamMembershipChanged(next.clubId(), next.id(), clock.instant(),
                Map.of("accountId", account.id(), "memberId", memberId, "after", roles, "status", next.status()), null, DomainEvent.Origin.SYSTEM));
        return account.id();
    }
}
