package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MigrationIdentityService {
    private final AccountRepository accounts; private final MembershipRepository memberships;
    private final AccountService service; private final Clock clock;
    public MigrationIdentityService(AccountRepository accounts,MembershipRepository memberships,AccountService service,Clock clock) {
        this.accounts=accounts; this.memberships=memberships; this.service=service; this.clock=clock;
    }
    public record Preview(String accountId, String memberId, boolean active) { }
    public Preview preview(String email) {
        return accounts.findByEmail(email).map(account -> new Preview(account.id(),memberships.findByAccountId(account.id()).map(Membership::memberId).orElse(null),
                account.status()==Account.Status.ACTIVE && memberships.findByAccountId(account.id()).map(m -> m.status()!=Membership.Status.ERASED).orElse(true))).orElse(null);
    }
    public String apply(String memberId,String email,String name,String locale,boolean active,Set<String> roles) {
        var before=preview(email);
        if (before!=null && (!before.active() || before.memberId()!=null && !before.memberId().equals(memberId))) { throw new ApiException(ErrorCode.INVALID_STATE); }
        var account=service.getOrCreate(email,name,locale,Account.Source.MIGRATION,null,true);
        var old=memberships.findByAccountId(account.id()).orElse(null);
        var mapped=new HashSet<Role>(); roles.forEach(r -> mapped.add(Role.valueOf(r)));
        // Preserve privileges granted outside migration; import never revokes an existing administrator.
        if (old!=null) { mapped.addAll(old.roles()); }
        var membership=new Membership(old==null ? UUID.randomUUID().toString() : old.id(),account.id(),TenantContext.require(),memberId,mapped,
                active ? Membership.Status.ACTIVE : Membership.Status.SUSPENDED,Role.MEMBER,false,
                old==null ? null : old.instructorId(),old==null ? clock.instant() : old.createdAt(),old==null ? null : old.lastAccessAt(),
                old==null ? null : old.adminProfile(),old==null ? 0 : old.version()+1,clock.instant(),null,null);
        memberships.replace(membership);
        return account.id();
    }
}
