package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.ClubAdminProvisioner;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (NO_COVERAGE) of {@link ClubAdminService} (club-as-code `admins` section): an admin needs provisioning
 * unless their membership already holds ADMIN, provisioning keeps the roles the membership already had, and the export lists only
 * the ADMIN memberships, sorted by email. Collaborators are mocks; fictional data.
 */
class ClubAdminServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final ClubAdminProvisioner.Admin LAURA = new ClubAdminProvisioner.Admin("laura@example.test", "Laura Example", "ca");

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final AccountService accountService = mock(AccountService.class);
    final MembershipService membershipService = mock(MembershipService.class);
    final ClubAdminService service = new ClubAdminService(accounts, memberships, accountService, membershipService);

    @BeforeEach void setUp() { TenantContext.open("club-a"); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    /** An account as AccountService.java:37-40 creates it for the console (Security(0, null, null, 0), no password). */
    static Account account(String id, String email, String name) {
        return new Account(id, email, name, "ca", null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, NOW, null, null, Account.Source.CONSOLE, null, null);
    }

    /** A club-as-code admin membership as MembershipService.java:58-63 creates it: no member, no default profile, version 0. */
    static Membership membership(String id, String accountId, Set<Role> roles) {
        return new Membership(id, accountId, "club-a", null, roles, Membership.Status.ACTIVE, null, false, null, NOW, null, null, 0, NOW,
                null, null);
    }

    /** A census member's membership as SignupIdentityService.java:39 creates it: the member's id, MEMBER as default profile. */
    static Membership memberOf(String id, String accountId, String memberId) {
        return new Membership(id, accountId, "club-a", memberId, Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER, false, null,
                NOW, null);
    }

    @Test void E11_T06_anUnknownAdminEmailNeedsProvisioning() {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.empty());

        assertThat(service.needsProvision(LAURA)).isTrue();
    }

    @Test void E11_T06_anAccountWhoseMembershipIsAlreadyAdminNeedsNoProvisioning() {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account("acc-1", "laura@example.test", "Laura Example")));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership("membership-1", "acc-1", Set.of(Role.ADMIN))));

        assertThat(service.needsProvision(LAURA)).isFalse();
    }

    @Test void E11_T06_anAccountWhoseMembershipIsNotAdminNeedsProvisioning() {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account("acc-1", "laura@example.test", "Laura Example")));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(memberOf("membership-1", "acc-1", "member-1")));

        assertThat(service.needsProvision(LAURA)).isTrue();
    }

    @Test void E11_T06_provisioningAnAdminKeepsTheRolesTheMembershipAlreadyHad() {
        when(accountService.getOrCreate("laura@example.test", "Laura Example", "ca", Account.Source.CONSOLE))
                .thenReturn(account("acc-1", "laura@example.test", "Laura Example"));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(memberOf("membership-1", "acc-1", "member-1")));

        service.provision(LAURA);

        verify(membershipService).setRoles("acc-1", Set.of(Role.MEMBER, Role.ADMIN));
    }

    @Test void E11_T06_theExportListsOnlyTheAdminMembershipsSortedByEmail() {
        when(memberships.findAll()).thenReturn(List.of(
                membership("membership-1", "acc-1", Set.of(Role.ADMIN)),
                memberOf("membership-2", "acc-2", "member-2"),
                membership("membership-3", "acc-3", Set.of(Role.ADMIN, Role.INSTRUCTOR))));
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "marc@example.test", "Marc Example")));
        when(accounts.findById("acc-2")).thenReturn(Optional.of(account("acc-2", "nuria@example.test", "Nuria Example")));
        when(accounts.findById("acc-3")).thenReturn(Optional.of(account("acc-3", "laura@example.test", "Laura Example")));

        assertThat(service.list()).containsExactly(
                new ClubAdminProvisioner.Admin("laura@example.test", "Laura Example", "ca"),
                new ClubAdminProvisioner.Admin("marc@example.test", "Marc Example", "ca"));
    }
}
