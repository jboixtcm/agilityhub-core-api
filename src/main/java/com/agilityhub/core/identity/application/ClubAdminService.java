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
    private final AccountService accountService;
    private final MembershipService membershipService;
    public ClubAdminService(AccountRepository accounts, MembershipRepository memberships, AccountService accountService, MembershipService membershipService) {
        this.accounts = accounts; this.memberships = memberships; this.accountService = accountService; this.membershipService = membershipService;
    }
    @Override public boolean needsProvision(Admin admin) {
        TenantContext.require();
        return accounts.findByEmail(admin.email()).flatMap(account -> memberships.findByAccountId(account.id()))
                .filter(membership -> membership.roles().contains(Role.ADMIN)).isEmpty();
    }
    @Override public void provision(Admin admin) {
        String clubId = TenantContext.require();
        var account = accountService.getOrCreate(admin.email(), admin.name(), admin.locale(), Account.Source.CONSOLE);
        var membership = memberships.findByAccountId(account.id());
        var roles = EnumSet.noneOf(Role.class);
        membership.ifPresent(value -> roles.addAll(value.roles()));
        roles.add(Role.ADMIN);
        membershipService.setRoles(account.id(), roles);
    }
    @Override public List<Admin> list() {
        return memberships.findAll().stream().filter(membership -> membership.roles().contains(Role.ADMIN))
                .map(membership -> accounts.findById(membership.accountId()).orElseThrow())
                .map(account -> new Admin(account.email(), account.name(), account.locale()))
                .sorted(java.util.Comparator.comparing(Admin::email)).toList();
    }
}
