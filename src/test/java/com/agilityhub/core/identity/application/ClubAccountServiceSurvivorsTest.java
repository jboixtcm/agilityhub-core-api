package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.ClubAccountProvisioner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link ClubAccountService#list} (club-as-code export of the `accounts` section): a membership left
 * without roles (suspended by `MembershipService.setRoles(…, {})`) is not exported as a seed account. Collaborators are mocks.
 */
class ClubAccountServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final ClubAccountService service = new ClubAccountService(accounts, memberships, mock(AccountService.class),
            mock(MembershipService.class), mock(PasswordHasher.class), mock(SeedPasswordPolicy.class));

    /** A seed account as AccountService.java:37-40 creates it (CONSOLE, Security(0, null, null, 0) without a seed password). */
    static Account account(String id, String email, String name) {
        return new Account(id, email, name, "ca", null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, NOW, null, null, Account.Source.CONSOLE, null, null);
    }

    /**
     * Seed memberships as MembershipService.java:58-63 writes them for `setRoles` without a previous membership: no member, no
     * default profile, version 0; `seed.roles = []` gives a SUSPENDED membership without roles (MembershipService.java:38).
     */
    static Membership seeded(String id, String accountId, Set<Role> roles, Membership.Status status) {
        return new Membership(id, accountId, "club-a", null, roles, status, null, false, null, NOW, null, null, 0, NOW, null, null);
    }

    @Test void E11_T06_aMembershipWithoutRolesIsNotExportedAsASeedAccount() {
        when(memberships.findAll()).thenReturn(List.of(
                seeded("membership-1", "acc-1", Set.of(Role.INSTRUCTOR), Membership.Status.ACTIVE),
                seeded("membership-2", "acc-2", Set.of(), Membership.Status.SUSPENDED)));
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "laura@example.test", "Laura Example")));
        when(accounts.findById("acc-2")).thenReturn(Optional.of(account("acc-2", "marc@example.test", "Marc Example")));

        assertThat(service.list()).extracting(ClubAccountProvisioner.SeedAccount::email).containsExactly("laura@example.test");
    }
}
