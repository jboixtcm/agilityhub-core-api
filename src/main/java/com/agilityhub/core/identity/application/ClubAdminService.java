package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.ClubAdminProvisioner;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ClubAdminService implements ClubAdminProvisioner {
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final Clock clock;
    public ClubAdminService(AccountRepository accounts, MembershipRepository memberships, Clock clock) {
        this.accounts = accounts; this.memberships = memberships; this.clock = clock;
    }
    @Override public boolean needsProvision(Admin admin) {
        TenantContext.require();
        return accounts.findByEmail(admin.email()).flatMap(account -> memberships.findByAccountId(account.id()))
                .filter(membership -> membership.roles().contains(Role.ADMIN)).isEmpty();
    }
    @Override public void provision(Admin admin) {
        String clubId = TenantContext.require();
        var account = accounts.findByEmail(admin.email()).orElseGet(() -> accounts.save(new Account(UUID.randomUUID().toString(),
                admin.email(), admin.name(), admin.locale(), null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), true, clock.instant())));
        var membership = memberships.findByAccountId(account.id());
        if (membership.isEmpty()) {
            memberships.insert(new Membership(UUID.randomUUID().toString(), account.id(), clubId, null,
                    Set.of(Role.ADMIN), Membership.Status.ACTIVE, Role.ADMIN));
        } else if (!membership.get().roles().contains(Role.ADMIN)) {
            var old = membership.get();
            var roles = EnumSet.copyOf(old.roles()); roles.add(Role.ADMIN);
            memberships.replace(new Membership(old.id(), old.accountId(), old.clubId(), old.memberId(), roles, old.status(), old.defaultProfile()));
        }
    }
    @Override public List<Admin> list() {
        return memberships.findAll().stream().filter(membership -> membership.roles().contains(Role.ADMIN))
                .map(membership -> accounts.findById(membership.accountId()).orElseThrow())
                .map(account -> new Admin(account.email(), account.name(), account.locale()))
                .sorted(java.util.Comparator.comparing(Admin::email)).toList();
    }
}
