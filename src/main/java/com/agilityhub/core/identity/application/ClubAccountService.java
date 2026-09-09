package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.ClubAccountProvisioner;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ClubAccountService implements ClubAccountProvisioner {
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final AccountService accountService;
    private final MembershipService membershipService;
    private final PasswordHasher passwords;
    private final SeedPasswordPolicy policy;

    public ClubAccountService(AccountRepository accounts, MembershipRepository memberships, AccountService accountService,
                              MembershipService membershipService, PasswordHasher passwords, SeedPasswordPolicy policy) {
        this.accounts = accounts; this.memberships = memberships; this.accountService = accountService;
        this.membershipService = membershipService; this.passwords = passwords; this.policy = policy;
    }
    @Override public void validate(SeedAccount seed, boolean allowSeedPasswords) {
        policy.resolve(seed.password(), allowSeedPasswords);
    }
    @Override public boolean needsProvision(SeedAccount seed) {
        TenantContext.require();
        return accounts.findByEmail(seed.email()).flatMap(account -> memberships.findByAccountId(account.id()))
                .filter(membership -> membership.roles().equals(roles(seed))).isEmpty();
    }
    @Override public void provision(SeedAccount seed, boolean allowSeedPasswords) {
        TenantContext.require();
        String password = policy.resolve(seed.password(), allowSeedPasswords);
        // Initial values are insert-only; another club's apply must never reset a global account.
        var account = accounts.findByEmail(seed.email()).orElseGet(() -> accountService.getOrCreate(
                seed.email(), seed.name(), seed.locale(), Account.Source.CONSOLE,
                password == null ? null : passwords.hash(password), seed.onboardingPending()));
        membershipService.setRoles(account.id(), roles(seed));
    }
    private Set<Role> roles(SeedAccount seed) {
        return seed.roles().stream().map(Role::valueOf).collect(Collectors.toUnmodifiableSet());
    }
    @Override public java.util.List<SeedAccount> list() {
        return memberships.findAll().stream().filter(membership -> !membership.roles().isEmpty())
                .map(membership -> {
                    var account = accounts.findById(membership.accountId()).orElseThrow();
                    return new SeedAccount(account.email(), account.name(), account.locale(),
                            membership.roles().stream().map(Enum::name).collect(Collectors.toCollection(java.util.TreeSet::new)),
                            null, account.onboardingPending());
                }).sorted(java.util.Comparator.comparing(SeedAccount::email)).toList();
    }
}
