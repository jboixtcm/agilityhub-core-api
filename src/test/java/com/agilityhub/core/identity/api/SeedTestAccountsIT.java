package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.SeedTestAccountsCommand;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.shared.application.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;

class SeedTestAccountsIT extends IdentityIntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.identity.application.AccountService accountService;
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.identity.application.MembershipService membershipService;
    SeedTestAccountsCommand command(String secret) {
        return new SeedTestAccountsCommand(accounts, memberships, passwords, configs, clock, accountService, membershipService, secret);
    }
    @Test void T_01_02_seedCreatesThreeFictionalAccountsAndIsRepeatableWithoutResettingCredentials() {
        var seed = command("Seed-example-password");
        var args = new DefaultApplicationArguments("identity:seed-test-accounts", "--club=club-b");
        seed.run(args); seed.run(new DefaultApplicationArguments("--core.command=identity:seed-test-accounts", "--club=club-b"));
        assertThat(mongo.findAll(Account.class)).hasSize(3);
        assertThat(mongo.findAll(Membership.class)).hasSize(4);
        try (var scope = TenantContext.open("club-b")) {
            for (Role role : Role.values()) {
                var account = accounts.findByEmail(role.name().toLowerCase() + "@example.test").orElseThrow();
                assertThat(passwords.verify(role == Role.ADMIN ? PASSWORD : "Seed-example-password", account.passwordHash())).isTrue();
                var membership = memberships.findByAccountId(account.id()).orElseThrow();
                assertThat(membership.roles()).contains(Role.MEMBER, role);
                assertThat(membership.defaultProfile()).isEqualTo(role);
                assertThat(membership.memberId()).isNull();
            }
        }
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_01_02_seedRequiresExplicitCommandClubAndEnvironmentPassword() {
        command("").run(new DefaultApplicationArguments());
        for (var args : new DefaultApplicationArguments[]{
                new DefaultApplicationArguments("identity:seed-test-accounts"),
                new DefaultApplicationArguments("identity:seed-test-accounts", "--club=club-a", "--club=club-b"),
                new DefaultApplicationArguments("identity:seed-test-accounts", "--club=missing")}) {
            assertThatThrownBy(() -> command("Example-password").run(args)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> command("").run(new DefaultApplicationArguments("identity:seed-test-accounts", "--club=club-a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mongo.findAll(Account.class)).hasSize(1);
    }
}
