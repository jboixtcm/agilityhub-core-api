package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 10) of {@link CensusIdentityService}: the display name SessionProjection.java:103 shows as a
 * booking's `createdByName` (the account's name when it has a membership in the club, empty otherwise), and the refusals of the
 * access email (S03 T-03-16) and of a reactivation whose account holds another member's membership (S03 T-03-06), which are
 * MEMBER_NOT_ACTIVE and never a NullPointerException. Collaborators are mocks; fictional data.
 */
class CensusIdentityServiceSurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final CensusIdentityService service = new CensusIdentityService(accounts, memberships, mock(MagicLinkService.class),
            mock(RateLimits.class), mock(CensusClubSettings.class));

    @BeforeEach void setUp() { TenantContext.open(CLUB); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    static Account account() {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW.minusSeconds(86_400));
    }

    /** The account's membership in the club; SUSPENDED without roles after a leave (RoleAssignmentService via TeamMembershipService). */
    static Membership membership(String memberId, Set<Role> roles, Membership.Status status) {
        return new Membership("membership-1", "acc-1", CLUB, memberId, roles, status, null, false, null,
                NOW.minusSeconds(86_400), NOW.minusSeconds(3_600), null, 2, NOW.minusSeconds(3_600), null, null);
    }

    // --- displayName -----------------------------------------------------------------------------------------------------------

    @Test void E11_T06_theDisplayNameOfAnAccountWithAMembershipIsTheAccountName() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership("member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE)));
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account()));

        assertThat(service.displayName("acc-1")).isEqualTo("Laura Example");
    }

    @Test void E11_T06_anAccountWithoutMembershipInTheClubHasAnEmptyDisplayName() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.empty());
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account()));

        assertThat(service.displayName("acc-1")).isEmpty();
    }

    // --- accessEmail -----------------------------------------------------------------------------------------------------------

    @Test void T_03_16_anAccountIdWithoutAccountHasNoAccessEmail() {
        when(accounts.findById("acc-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accessEmail("acc-404"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MEMBER_NOT_ACTIVE));
    }

    // --- reactivate ------------------------------------------------------------------------------------------------------------

    @Test void T_03_06_reactivatingAMemberWhoseAccountHoldsAnotherMembersMembershipIsRefused() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership("member-2", Set.of(), Membership.Status.SUSPENDED)));

        assertThatThrownBy(() -> service.reactivate("member-1", "acc-1"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MEMBER_NOT_ACTIVE));
        verify(memberships, never()).saveTeam(any());
    }
}
