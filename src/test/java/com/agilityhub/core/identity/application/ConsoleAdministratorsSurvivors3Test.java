package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.ConsoleAdministratorAccess;
import com.agilityhub.core.platform.application.PlatformClubChanges;
import com.agilityhub.core.shared.application.TeamWriteLock;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 10) of {@link ConsoleAdministrators} (S17 R-17-09, T-17-08: the console's administrator list,
 * invitation and removal): only ADMIN memberships are listed, an administrator whose account is gone is NOT_FOUND, the snapshot
 * (the audit `before` and the response's `admins`) carries each administrator's account and status, inviting an active member
 * does not resume anything while re-inviting a suspended one does, and removing an administrator records the club change before
 * suspending the membership. Collaborators are mocks; fictional data.
 */
class ConsoleAdministratorsSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant LAST_ACCESS = NOW.minusSeconds(3_600);
    /** An ACTIVE club (not onboarding) at version 7, as PlatformClubChanges.administration() reads it. */
    static final PlatformClubChanges.Administration CLUB = new PlatformClubChanges.Administration("club-a", "ca", false, 7);

    final AccountService accounts = mock(AccountService.class);
    final AccountRepository accountRows = mock(AccountRepository.class);
    final MembershipService membershipService = mock(MembershipService.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final PlatformClubChanges clubs = mock(PlatformClubChanges.class);
    final TeamWriteLock team = mock(TeamWriteLock.class);
    final ConsoleAdministrators admins = new ConsoleAdministrators(accounts, accountRows, membershipService, memberships, clubs, team);

    final Account laura = account("acc-1", "laura@example.test", "Laura Example");
    final Account marc = account("acc-2", "marc@example.test", "Marc Example");

    @BeforeEach void setUp() {
        when(clubs.administration()).thenReturn(CLUB);
        when(accountRows.findById("acc-1")).thenReturn(Optional.of(laura));
        when(accountRows.findById("acc-2")).thenReturn(Optional.of(marc));
    }

    static Account account(String id, String email, String name) {
        return new Account(id, email, name, "ca", null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, NOW.minusSeconds(86_400 * 90L));
    }

    static Membership membership(String accountId, String memberId, Set<Role> roles, Membership.Status status) {
        return new Membership("ms-" + accountId, accountId, "club-a", memberId, roles, status, null, false, null,
                NOW.minusSeconds(86_400 * 30L), LAST_ACCESS, null, 1, LAST_ACCESS, null, null);
    }

    static ConsoleAdministratorAccess.Admin admin(Account account, String status) {
        return new ConsoleAdministratorAccess.Admin(account.id(), account.name(), account.email(), status, LAST_ACCESS);
    }

    // --- list, view, snapshot ------------------------------------------------------------------------------------------------

    @Test void T_17_08_theAdministratorListLeavesOutMembershipsWithoutTheAdminRole() {
        when(memberships.findAll()).thenReturn(List.of(membership("acc-1", "member-1", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE),
                membership("acc-2", "member-2", Set.of(Role.MEMBER), Membership.Status.ACTIVE)));

        assertThat(admins.list()).containsExactly(admin(laura, "ACTIVE"));
    }

    @Test void T_17_08_anAdministratorWhoseAccountIsGoneIsNotFound() {
        when(memberships.findAll()).thenReturn(List.of(membership("acc-404", "member-9", Set.of(Role.ADMIN), Membership.Status.ACTIVE)));
        when(accountRows.findById("acc-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> admins.list())
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_17_08_theSnapshotListsEachAdministratorsAccountAndStatus() {
        when(memberships.findAll()).thenReturn(List.of(membership("acc-1", "member-1", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE),
                membership("acc-2", "member-2", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.SUSPENDED)));

        assertThat(admins.snapshot()).isEqualTo(new ConsoleAdministratorAccess.Change("club-a", List.of(
                Map.of("accountId", "acc-1", "status", "ACTIVE"), Map.of("accountId", "acc-2", "status", "SUSPENDED")), null));
    }

    // --- invite --------------------------------------------------------------------------------------------------------------

    @Test void T_17_08_invitingAnActiveMemberPromotesItWithoutResumingAnything() {
        var member = membership("acc-2", "member-2", Set.of(Role.MEMBER), Membership.Status.ACTIVE);
        var promoted = membership("acc-2", "member-2", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE);
        when(accounts.getOrCreate("marc@example.test", "Marc Example", "ca", Account.Source.CONSOLE)).thenReturn(marc);
        when(memberships.findByAccountId("acc-2")).thenReturn(Optional.of(member));
        when(membershipService.setRoles(eq("acc-2"), any())).thenReturn(promoted);
        when(memberships.findAll()).thenReturn(List.of(promoted));

        var change = admins.invite("marc@example.test", "Marc Example");

        verify(membershipService).setRoles("acc-2", Set.of(Role.MEMBER, Role.ADMIN));
        verify(membershipService, never()).resume(any());
        verify(clubs).administratorChange(CLUB, Map.of("adminInvitation", "acc-2"));
        assertThat(change).isEqualTo(new ConsoleAdministratorAccess.Change("club-a",
                List.of(Map.of("accountId", "acc-2", "status", "ACTIVE")), admin(marc, "ACTIVE")));
    }

    @Test void T_17_08_reinvitingASuspendedAdministratorResumesItsMembership() {
        var suspended = membership("acc-2", "member-2", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.SUSPENDED);
        var resumed = membership("acc-2", "member-2", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE);
        when(accounts.getOrCreate("marc@example.test", "Marc Example", "ca", Account.Source.CONSOLE)).thenReturn(marc);
        when(memberships.findByAccountId("acc-2")).thenReturn(Optional.of(suspended));
        when(membershipService.setRoles(eq("acc-2"), any())).thenReturn(suspended);
        when(membershipService.resume("acc-2")).thenReturn(resumed);
        when(memberships.findAll()).thenReturn(List.of(resumed));

        var change = admins.invite("marc@example.test", "Marc Example");

        verify(membershipService).resume("acc-2");
        assertThat(change.admin()).isEqualTo(admin(marc, "ACTIVE"));
    }

    // --- remove --------------------------------------------------------------------------------------------------------------

    @Test void T_17_08_removingAnAccountWithoutMembershipIsNotFound() {
        when(memberships.findByAccountId("acc-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> admins.remove("acc-404"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(clubs, never()).administratorChange(any(), any());
        verify(membershipService, never()).suspend(any());
    }

    @Test void T_17_08_removingAnAdministratorRecordsTheClubChangeAndSuspendsTheMembership() {
        when(memberships.findByAccountId("acc-2")).thenReturn(Optional.of(
                membership("acc-2", "member-2", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE)));
        when(memberships.activeAdmins()).thenReturn(2L);
        when(memberships.findAll()).thenReturn(List.of(membership("acc-1", "member-1", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE)));

        admins.remove("acc-2");

        var order = inOrder(clubs, membershipService);
        order.verify(clubs).administratorChange(CLUB, Map.of("adminRemoved", "acc-2"));
        order.verify(membershipService).suspend("acc-2");
    }
}
