package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.domain.TeamMembershipChanged;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (NO_COVERAGE) of {@link DemoIdentityService#link} (demo seed of census members): only `@example.test`
 * addresses, never a membership already linked to a member or not active; a new membership is MEMBER and active or suspended as
 * asked; an existing staff membership keeps its id, roles, remembered profile and dates and moves to the next version; the
 * `MembershipChanged` payload carries the roles before/after. The mutants that swap `old == null` for the instructor, admin
 * profile, creation date, last access and version (DemoIdentityService.java:31-32) dereference the missing membership of a new
 * account and fail that test. Collaborators are mocks; fictional data.
 */
class DemoIdentityServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant CREATED = Instant.parse("2026-01-10T09:00:00Z");
    static final Instant ACCESSED = Instant.parse("2026-10-01T18:00:00Z");

    final AccountService accountService = mock(AccountService.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final EventPublisher events = mock(EventPublisher.class);
    final DemoIdentityService service = new DemoIdentityService(accountService, accounts, memberships, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void setUp() { TenantContext.open(CLUB); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    /**
     * The account `getOrCreate` returns (AccountService.java:37-40, CONSOLE). Every account is created ACTIVE and no production
     * code changes an account's status, so a non-active existing account (DemoIdentityService.java:24) is not a fixture here.
     */
    static Account account() {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, CREATED, null, null, Account.Source.CONSOLE, null, null);
    }

    /**
     * A staff membership without a member: club-as-code `admins` (ClubAdminService.java:34-39 via MembershipService.java:58-63),
     * whose holder later chose and remembered the ADMIN profile (TokenService.java:153, MembershipRepository.java:31-34) and kept
     * signing in (MembershipRepository.java:36-38), after four role changes. Instructor and admin profile are written only for a
     * member's membership (RoleAssignmentService.java:51-54 `forMember`), and `createdByAccountId`/`updatedByAccountId` stay null
     * outside the team screens, so they are null here.
     */
    static Membership staff() {
        return new Membership("membership-1", "acc-1", CLUB, null, Set.of(Role.ADMIN), Membership.Status.ACTIVE, Role.ADMIN, true, null,
                CREATED, ACCESSED, null, 4, ACCESSED, null, null);
    }

    /** A membership already linked to a census member (SignupIdentityService.java:39: MEMBER, the member's id). */
    static Membership linkedTo(String memberId) {
        return new Membership("membership-1", "acc-1", CLUB, memberId, Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER, false,
                null, CREATED, ACCESSED);
    }

    /** A staff membership whose roles were all removed (MembershipService.java:38: SUSPENDED, no roles, no default profile). */
    static Membership suspendedStaff() {
        return new Membership("membership-1", "acc-1", CLUB, null, Set.of(), Membership.Status.SUSPENDED, null, false, null,
                CREATED, ACCESSED, null, 5, ACCESSED, null, null);
    }

    private Membership replaced() {
        var captor = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).replace(captor.capture());
        return captor.getValue();
    }

    private TeamMembershipChanged published() {
        var captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(captor.capture());
        return (TeamMembershipChanged) captor.getValue();
    }

    @Test void E11_T06_anAddressOutsideExampleTestIsRefused() {
        assertThatThrownBy(() -> service.link("member-1", "laura@example.com", "Laura Example", "ca", true))
                .isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(accountService, memberships, events);
    }

    @Test void E11_T06_aNewAccountGetsAnActiveMemberMembershipLinkedToTheMember() {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.empty());
        when(accountService.getOrCreate("laura@example.test", "Laura Example", "ca", Account.Source.CONSOLE, null, false))
                .thenReturn(account());
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.empty());

        assertThat(service.link("member-1", "laura@example.test", "Laura Example", "ca", true)).isEqualTo("acc-1");

        var next = replaced();
        assertThat(next.id()).isNotNull().isNotEqualTo("membership-1");
        assertThat(next.accountId()).isEqualTo("acc-1");
        assertThat(next.clubId()).isEqualTo(CLUB);
        assertThat(next.memberId()).isEqualTo("member-1");
        assertThat(next.roles()).containsExactly(Role.MEMBER);
        assertThat(next.status()).isEqualTo(Membership.Status.ACTIVE);
        assertThat(next.defaultProfile()).isEqualTo(Role.MEMBER);
        assertThat(next.rememberProfile()).isFalse();
        assertThat(next.instructorId()).isNull();
        assertThat(next.createdAt()).isEqualTo(NOW);
        assertThat(next.lastAccessAt()).isNull();
        assertThat(next.adminProfile()).isNull();
        assertThat(next.version()).isZero();
        assertThat(next.updatedAt()).isEqualTo(NOW);
        var event = published();
        assertThat(event.payload()).containsEntry("before", Set.of()).containsEntry("after", Set.of(Role.MEMBER))
                .containsEntry("status", "ACTIVE").containsEntry("memberId", "member-1").containsEntry("accountId", "acc-1");
    }

    @Test void E11_T06_anExistingStaffMembershipIsLinkedKeepingItsDataAtTheNextVersion() {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account()));
        when(accountService.getOrCreate("laura@example.test", "Laura Example", "ca", Account.Source.CONSOLE, null, false))
                .thenReturn(account());
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(staff()));

        assertThat(service.link("member-1", "laura@example.test", "Laura Example", "ca", false)).isEqualTo("acc-1");

        var next = replaced();
        assertThat(next.id()).isEqualTo("membership-1");
        assertThat(next.memberId()).isEqualTo("member-1");
        assertThat(next.roles()).containsExactly(Role.ADMIN);
        assertThat(next.status()).isEqualTo(Membership.Status.SUSPENDED);
        assertThat(next.defaultProfile()).isEqualTo(Role.ADMIN);
        assertThat(next.rememberProfile()).isTrue();
        assertThat(next.instructorId()).isNull();
        assertThat(next.createdAt()).isEqualTo(CREATED);
        assertThat(next.lastAccessAt()).isEqualTo(ACCESSED);
        assertThat(next.adminProfile()).isNull();
        assertThat(next.version()).isEqualTo(5);
        assertThat(next.updatedAt()).isEqualTo(NOW);
        var event = published();
        assertThat(event.payload()).containsEntry("before", Set.of(Role.ADMIN)).containsEntry("after", Set.of(Role.ADMIN))
                .containsEntry("status", "SUSPENDED");
    }

    @Test void E11_T06_aMembershipAlreadyLinkedToAMemberIsNotRelinked() {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account()));
        when(accountService.getOrCreate("laura@example.test", "Laura Example", "ca", Account.Source.CONSOLE, null, false))
                .thenReturn(account());
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(linkedTo("member-9")));

        assertThatThrownBy(() -> service.link("member-1", "laura@example.test", "Laura Example", "ca", true))
                .isInstanceOf(ApiException.class).hasMessage("CLUB_NOT_EMPTY");
        verify(memberships, never()).replace(any());
    }

    @Test void E11_T06_aSuspendedMembershipIsNotRelinked() {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(account()));
        when(accountService.getOrCreate("laura@example.test", "Laura Example", "ca", Account.Source.CONSOLE, null, false))
                .thenReturn(account());
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(suspendedStaff()));

        assertThatThrownBy(() -> service.link("member-1", "laura@example.test", "Laura Example", "ca", true))
                .isInstanceOf(ApiException.class).hasMessage("CLUB_NOT_EMPTY");
        verify(memberships, never()).replace(any());
    }
}
