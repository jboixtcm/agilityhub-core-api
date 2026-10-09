package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * E11-T06 PIT survivors of {@link MigrationIdentityService} (S18 R-18-14, migration preview and apply): the preview of an email's
 * account and membership, the membership check of a re-execution, and `apply` for a new member, an existing member (roles kept,
 * dates and version carried over, a former member suspended), an existing account without member, and the refused conflicts.
 * Collaborators are mocks; fictional data.
 */
class MigrationIdentityServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant CREATED = Instant.parse("2024-02-01T10:00:00Z");
    static final String EMAIL = "laura@example.test";

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final AccountService service = mock(AccountService.class);
    final MigrationIdentityService migration = new MigrationIdentityService(accounts, memberships, service, Clock.fixed(NOW, ZoneOffset.UTC));
    TenantContext.Scope tenant;

    @BeforeEach void setUp() { TenantContext.clear(); tenant = TenantContext.open("club-a"); }

    @AfterEach void clearTenant() { tenant.close(); TenantContext.clear(); }

    /** Accounts are only ever ACTIVE (AccountService.java:37, SignupIdentityService.java:26; no writer changes `status`). */
    static Account account(String id) {
        return new Account(id, EMAIL, "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0),
                Map.of(), true, CREATED, null, null, Account.Source.MIGRATION, null, null);
    }

    /**
     * A stored membership; an active administrator profile goes with the ADMIN role (RoleAssignmentService.java:139,149,179-180
     * set `active` from the ADMIN role), and `Membership.Status.ERASED` is never written (only read).
     */
    static Membership membership(String memberId, Set<Role> roles, Membership.Status status) {
        return new Membership("mem-1", "acc-1", "club-a", memberId, roles, status, roles.contains(Role.MEMBER) ? Role.MEMBER : null, false, "ins-1",
                CREATED, NOW.minus(Duration.ofDays(3)),
                roles.contains(Role.ADMIN) ? new Membership.AdminProfile("Laura", LocalDate.of(2024, 3, 1), true) : null, 3,
                CREATED.plus(Duration.ofDays(10)), null, null);
    }

    private void existing(Membership membership) {
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(account("acc-1")));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.ofNullable(membership));
    }

    // --- preview and hasMembership -----------------------------------------------------------------------------------------

    @Test void E11_T06_thePreviewOfAnActiveMemberIsActiveWithItsMember() {
        existing(membership("member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE));

        assertThat(migration.preview(EMAIL)).isEqualTo(new MigrationIdentityService.Preview("acc-1", "member-1", true));
    }

    @Test void E11_T06_thePreviewOfAnActiveAccountWithoutMembershipIsActiveWithoutMember() {
        existing(null);

        assertThat(migration.preview(EMAIL)).isEqualTo(new MigrationIdentityService.Preview("acc-1", null, true));
    }

    @Test void E11_T06_anUnknownEmailHasNoPreview() {
        assertThat(migration.preview("nobody@example.test")).isNull();
    }

    @Test void E11_T06_hasMembershipTellsWhetherTheMemberHasAMembership() {
        when(memberships.findByMemberId("member-1")).thenReturn(Optional.of(membership("member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE)));

        assertThat(migration.hasMembership("member-1")).isTrue();
        assertThat(migration.hasMembership("member-2")).isFalse();
    }

    // --- apply -------------------------------------------------------------------------------------------------------------

    private Membership replaced() {
        var captor = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).replace(captor.capture());
        return captor.getValue();
    }

    @Test void E11_T06_applyingANewMemberCreatesItsAccountAndAFreshMembership() {
        when(service.getOrCreate(EMAIL, "Laura Example", "ca", Account.Source.MIGRATION, null, true)).thenReturn(account("acc-new"));

        assertThat(migration.apply("member-1", EMAIL, "Laura Example", "ca", true, Set.of("MEMBER", "INSTRUCTOR"))).isEqualTo("acc-new");

        var membership = replaced();
        assertThat(membership.id()).isNotBlank().isNotEqualTo("mem-1");
        assertThat(membership).isEqualTo(new Membership(membership.id(), "acc-new", "club-a", "member-1",
                Set.of(Role.MEMBER, Role.INSTRUCTOR), Membership.Status.ACTIVE, Role.MEMBER, false, null, NOW, null, null, 0, NOW, null, null));
    }

    @Test void E11_T06_reapplyingAFormerMemberKeepsItsRolesAndDatesAndSuspendsIt() {
        var old = membership("member-1", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE);
        existing(old);
        when(service.getOrCreate(EMAIL, "Laura Example", "ca", Account.Source.MIGRATION, null, true)).thenReturn(account("acc-1"));

        assertThat(migration.apply("member-1", EMAIL, "Laura Example", "ca", false, Set.of("MEMBER"))).isEqualTo("acc-1");

        assertThat(replaced()).isEqualTo(new Membership("mem-1", "acc-1", "club-a", "member-1", Set.of(Role.MEMBER, Role.ADMIN),
                Membership.Status.SUSPENDED, Role.MEMBER, false, "ins-1", CREATED, NOW.minus(Duration.ofDays(3)),
                new Membership.AdminProfile("Laura", LocalDate.of(2024, 3, 1), true), 4, NOW, null, null));
    }

    @Test void E11_T06_anExistingAccountWithoutMemberIsLinkedToTheMigratedMember() {
        // A club-as-code staff account: its membership has roles but no census member yet.
        var staff = new Membership("mem-1", "acc-1", "club-a", null, Set.of(Role.ADMIN), Membership.Status.ACTIVE, null, false, null, CREATED, null,
                null, 0, CREATED, null, null);
        existing(staff);
        when(service.getOrCreate(EMAIL, "Laura Example", "ca", Account.Source.MIGRATION, null, true)).thenReturn(account("acc-1"));

        migration.apply("member-1", EMAIL, "Laura Example", "ca", true, Set.of("MEMBER"));

        var membership = replaced();
        assertThat(membership.memberId()).isEqualTo("member-1");
        assertThat(membership.roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.ADMIN);
        assertThat(membership.version()).isEqualTo(1);
    }

    @Test void E11_T06_anEmailAlreadyLinkedToAnotherMemberIsRefused() {
        existing(membership("member-9", Set.of(Role.MEMBER), Membership.Status.ACTIVE));

        assertThatThrownBy(() -> migration.apply("member-1", EMAIL, "Laura Example", "ca", true, Set.of("MEMBER")))
                .isInstanceOf(ApiException.class).hasMessage("INVALID_STATE");
        verify(memberships, never()).replace(any());
    }
}
