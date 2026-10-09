package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.domain.TeamMembershipChanged;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditActor;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link TeamMembershipService} (S05 team catalogs, R-05 administrators/instructors; S01 R-01-07): the
 * membership views and lookups, the administrators list (filter, inactive ones, order), the active-member check, the role
 * snapshot, and every branch of `save` (no change, a new administrator, an instructor link, a member who leaves). Collaborators are
 * mocks; fictional data.
 */
class TeamMembershipServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant CREATED = Instant.parse("2025-09-01T10:00:00Z");
    static final LocalDate SINCE = LocalDate.of(2026, 9, 1);

    final MembershipRepository memberships = mock(MembershipRepository.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountSessionRepository sessions = mock(AccountSessionRepository.class);
    final AuditActorProvider actors = mock(AuditActorProvider.class);
    final AuditWriter audit = mock(AuditWriter.class);
    final EventPublisher events = mock(EventPublisher.class);
    final TeamMembershipService team = new TeamMembershipService(memberships, accounts, sessions, actors, audit, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void setUp() {
        // AuditConfiguration:25-57 for an ADMIN bearer: `support` is the absent `support` claim (no issuer writes it), so null.
        when(actors.current()).thenReturn(new AuditActor("acc-admin", "Anna Admin", "ADMIN", null, null, "203.0.113.5",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) Gecko/20100101 Firefox/131.0", "trace-1"));
    }

    /**
     * A stored membership as the writers leave it: no writer ever sets `createdByAccountId` (SignupIdentityService.java:39 uses the
     * short constructor; MembershipService.java:63, MigrationIdentityService.java:36, DemoIdentityService.java:33 pass null or the old
     * value), and a non-MEMBER default profile exists only remembered (MembershipRepository.java:33 stores it only with remember).
     */
    static Membership membership(String id, String memberId, Set<Role> roles, Membership.Status status, Role defaultProfile, boolean remember,
                                 String instructorId, Membership.AdminProfile admin, long version) {
        return new Membership(id, "acc-" + memberId, "club-a", memberId, roles, status, defaultProfile, remember, instructorId, CREATED,
                NOW.minus(Duration.ofDays(2)), admin, version, CREATED, null, "acc-admin");
    }

    /** Accounts are only ever ACTIVE (AccountService.java:37, SignupIdentityService.java:26; no writer changes `status`). */
    static Account account(String id) {
        return new Account(id, "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, CREATED);
    }

    // --- views and lookups -------------------------------------------------------------------------------------------------

    @Test void E11_T06_getViewsAnAdministratorsMembershipWithItsProfile() {
        when(memberships.findById("mem-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER, Role.ADMIN),
                Membership.Status.ACTIVE, Role.ADMIN, true, null, new Membership.AdminProfile("Laura", SINCE, true), 4)));

        var view = team.get("mem-1");

        assertThat(view).isEqualTo(new TeamMembershipService.TeamMembership("mem-1", "acc-member-1", "member-1", Set.of("MEMBER", "ADMIN"),
                "ACTIVE", null, new TeamMembershipService.AdminProfile("Laura", SINCE, true), 4));
    }

    @Test void E11_T06_getViewsAPlainMemberWithoutAdministratorProfile() {
        when(memberships.findById("mem-2")).thenReturn(Optional.of(membership("mem-2", "member-2", Set.of(Role.MEMBER),
                Membership.Status.ACTIVE, Role.MEMBER, false, null, null, 0)));

        assertThat(team.get("mem-2").adminProfile()).isNull();
    }

    @Test void E11_T06_anUnknownTeamMembershipIsNotFound() {
        when(memberships.findById("mem-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> team.get("mem-404")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void E11_T06_aMembersTeamMembershipIsFoundByMember() {
        when(memberships.findByMemberId("member-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER, Role.INSTRUCTOR),
                Membership.Status.ACTIVE, Role.MEMBER, false, "ins-1", null, 2)));

        assertThat(team.findForMember("member-1")).hasValueSatisfying(view -> assertThat(view.instructorId()).isEqualTo("ins-1"));
        assertThat(team.forMember("member-1").roles()).containsExactlyInAnyOrder("MEMBER", "INSTRUCTOR");
    }

    @Test void E11_T06_theAccountNameIsTheAccountsName() {
        when(accounts.findById("acc-member-1")).thenReturn(Optional.of(account("acc-member-1")));

        assertThat(team.accountName("acc-member-1")).isEqualTo("Laura Example");
    }

    @Test void E11_T06_activeAdminsCountsTheClubsActiveAdministrators() {
        when(memberships.activeAdmins()).thenReturn(2L);

        assertThat(team.activeAdmins()).isEqualTo(2L);
    }

    // --- administrators ----------------------------------------------------------------------------------------------------

    private void team() {
        when(memberships.findAll()).thenReturn(List.of(
                membership("mem-1", "member-1", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE, Role.MEMBER, false, null,
                        new Membership.AdminProfile("Zoe", SINCE, true), 1),
                membership("mem-2", "member-2", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER, false, null,
                        new Membership.AdminProfile("Anna", SINCE.minusYears(2), false), 3),
                membership("mem-3", "member-3", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER, false, null, null, 0)));
    }

    @Test void E11_T06_administratorsIncludingInactiveOnesAreOrderedByShortName() {
        team();

        assertThat(team.administrators(true)).extracting(TeamMembershipService.TeamMembership::id).containsExactly("mem-2", "mem-1");
    }

    @Test void E11_T06_activeAdministratorsLeaveOutTheInactiveOnes() {
        team();

        assertThat(team.administrators(false)).extracting(TeamMembershipService.TeamMembership::id).containsExactly("mem-1");
    }

    // --- requireActive -----------------------------------------------------------------------------------------------------

    static TeamMembershipService.TeamMembership view(String accountId, String status) {
        return new TeamMembershipService.TeamMembership("mem-1", accountId, "member-1", Set.of("MEMBER"), status, null, null, 1);
    }

    @Test void E11_T06_anActiveMembershipOfAnActiveAccountIsActive() {
        when(accounts.findById("acc-member-1")).thenReturn(Optional.of(account("acc-member-1")));

        assertThatCode(() -> team.requireActive(view("acc-member-1", "ACTIVE"), "acc-member-1")).doesNotThrowAnyException();
    }

    @Test void E11_T06_aSuspendedMembershipIsNotAnActiveMember() {
        when(accounts.findById("acc-member-1")).thenReturn(Optional.of(account("acc-member-1")));

        assertThatThrownBy(() -> team.requireActive(view("acc-member-1", "SUSPENDED"), "acc-member-1"))
                .isInstanceOf(ApiException.class).hasMessage("MEMBER_NOT_ACTIVE");
    }

    @Test void E11_T06_aMembershipOfAnotherAccountIsNotTheMembersActiveMembership() {
        when(accounts.findById("acc-member-9")).thenReturn(Optional.of(account("acc-member-9")));

        assertThatThrownBy(() -> team.requireActive(view("acc-member-1", "ACTIVE"), "acc-member-9"))
                .isInstanceOf(ApiException.class).hasMessage("MEMBER_NOT_ACTIVE");
    }

    // --- roleSnapshot and save ---------------------------------------------------------------------------------------------

    @Test void E11_T06_theRoleSnapshotHasTheSortedRolesAndTheMember() {
        when(memberships.findById("mem-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER, Role.ADMIN),
                Membership.Status.ACTIVE, Role.MEMBER, false, null, new Membership.AdminProfile("Laura", SINCE, true), 4)));

        assertThat(team.roleSnapshot("mem-1")).isEqualTo(Map.of("roles", new TreeSet<>(Set.of("ADMIN", "MEMBER")), "memberId", "member-1"));
    }

    @Test void E11_T06_savingTheSameRolesInstructorAndProfileWritesNothing() {
        when(memberships.findById("mem-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER),
                Membership.Status.ACTIVE, Role.MEMBER, false, null, null, 2)));

        var result = team.save("mem-1", Set.of("MEMBER"), null, null, null);

        assertThat(result).isEqualTo(Map.of("roles", new TreeSet<>(Set.of("MEMBER")), "memberId", "member-1"));
        verify(memberships, never()).saveTeam(any());
        verifyNoInteractions(accounts, sessions, events, audit);
    }

    @Test void E11_T06_aNewAdministratorKeepsTheRememberedProfileAndIsAuditedAndPublished() {
        when(memberships.findById("mem-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER),
                Membership.Status.ACTIVE, Role.MEMBER, true, null, null, 3)));
        var profile = new TeamMembershipService.AdminProfile("Laura", SINCE, true);

        var result = team.save("mem-1", Set.of("MEMBER", "ADMIN"), null, profile, null);

        var saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveTeam(saved.capture());
        assertThat(saved.getValue()).isEqualTo(new Membership("mem-1", "acc-member-1", "club-a", "member-1", Set.of(Role.MEMBER, Role.ADMIN),
                Membership.Status.ACTIVE, Role.MEMBER, true, null, CREATED, NOW.minus(Duration.ofDays(2)),
                new Membership.AdminProfile("Laura", SINCE, true), 4, NOW, null, "acc-admin"));
        verify(accounts).touchSessions("acc-member-1");
        verify(sessions, never()).revokeClub(any(), any(), any());
        var event = ArgumentCaptor.forClass(TeamMembershipChanged.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
        assertThat(event.getValue().actorAccountId()).isEqualTo("acc-admin");
        assertThat(event.getValue().payload()).containsEntry("before", new TreeSet<>(Set.of(Role.MEMBER)))
                .containsEntry("after", new TreeSet<>(Set.of(Role.MEMBER, Role.ADMIN)));
        verify(audit).write(new AuditCommand(AuditAction.CATALOG_CHANGED, "Administrator", "mem-1", "member-1", Map.of(),
                Map.of("shortName", "Laura", "since", SINCE, "active", true), null));
        assertThat(result).isEqualTo(Map.of("roles", new TreeSet<>(Set.of("ADMIN", "MEMBER")), "memberId", "member-1"));
    }

    @Test void E11_T06_linkingAnInstructorWithoutRoleChangeNeitherTouchesSessionsNorPublishes() {
        // The signup's default profile, not remembered (SignupIdentityService.java:39).
        when(memberships.findById("mem-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER, Role.INSTRUCTOR),
                Membership.Status.ACTIVE, Role.MEMBER, false, null, null, 5)));

        team.save("mem-1", Set.of("MEMBER", "INSTRUCTOR"), "ins-1", null, null);

        var saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveTeam(saved.capture());
        assertThat(saved.getValue().instructorId()).isEqualTo("ins-1");
        assertThat(saved.getValue().defaultProfile()).isEqualTo(Role.MEMBER);
        assertThat(saved.getValue().rememberProfile()).isFalse();
        assertThat(saved.getValue().version()).isEqualTo(6);
        verify(accounts, never()).touchSessions(any());
        verify(events, never()).publish(any());
        verify(audit).write(any(AuditCommand.class));
    }

    @Test void E11_T06_aRemovedAdministratorLosesTheRememberedAdminProfile() {
        when(memberships.findById("mem-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER, Role.ADMIN),
                Membership.Status.ACTIVE, Role.ADMIN, true, null, new Membership.AdminProfile("Laura", SINCE, true), 7)));

        team.save("mem-1", Set.of("MEMBER"), null, null, null);

        var saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveTeam(saved.capture());
        assertThat(saved.getValue().defaultProfile()).isNull();
        assertThat(saved.getValue().rememberProfile()).isFalse();
        assertThat(saved.getValue().adminProfile()).isNull();
        verify(audit).write(new AuditCommand(AuditAction.CATALOG_CHANGED, "Administrator", "mem-1", "member-1",
                Map.of("shortName", "Laura", "since", SINCE, "active", true), Map.of(), null));
    }

    @Test void E11_T06_aMemberWhoLeavesIsSuspendedAndLosesTheClubsSessions() {
        // RoleAssignmentService.memberLeft: a census consumer, no request user, so the actor has no account.
        when(actors.current()).thenReturn(new AuditActor(null, null, "SYSTEM", null, null, null, null, "trace-2"));
        when(memberships.findById("mem-1")).thenReturn(Optional.of(membership("mem-1", "member-1", Set.of(Role.MEMBER),
                Membership.Status.ACTIVE, Role.MEMBER, false, null, null, 2)));

        team.save("mem-1", Set.of(), null, null, null);

        var saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveTeam(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(Membership.Status.SUSPENDED);
        assertThat(saved.getValue().roles()).isEmpty();
        verify(accounts).touchSessions("acc-member-1");
        verify(sessions).revokeClub("acc-member-1", "club-a", NOW);
        var event = ArgumentCaptor.forClass(TeamMembershipChanged.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().origin()).isEqualTo(DomainEvent.Origin.SYSTEM);
    }
}
