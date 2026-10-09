package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import java.time.Duration;
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
 * E11-T06 PIT survivors (NO_COVERAGE) of {@link CensusIdentityService}, the identity side of the census (S03 access resend, member
 * reactivation): the access email of an active account with an active membership, its refusals, the per-email/per-IP magic-link
 * rate limit of the resend (production limits 10/h per email, 60/h per IP), the resend delivery and the reactivation of a
 * suspended membership. Collaborators are mocks, except a real {@link RateLimits}; fictional data.
 */
class CensusIdentityServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final MagicLinkService links = mock(MagicLinkService.class);
    final CensusClubSettings clubs = mock(CensusClubSettings.class);
    final RateLimits limits = new RateLimits(true, Map.of(
            RateLimits.Route.MAGIC_LINK_EMAIL, new RateLimits.Limit(10, Duration.ofHours(1)),
            RateLimits.Route.MAGIC_LINK_IP, new RateLimits.Limit(60, Duration.ofHours(1))), Clock.fixed(NOW, ZoneOffset.UTC));
    final CensusIdentityService service = new CensusIdentityService(accounts, memberships, links, limits, clubs);

    @BeforeEach void setUp() { TenantContext.open(CLUB); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    /**
     * Every account is created ACTIVE (AccountService.java:37, SignupIdentityService.java:26) and no production code changes an
     * account's status (AccountRepository has no status update and its inherited `save` has no caller), so a blocked account is not
     * a fixture here.
     */
    static Account account() {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW.minusSeconds(86_400));
    }

    /**
     * The member's membership as the census leaves it: ACTIVE with MEMBER while the member is active, SUSPENDED without roles
     * after the leave (RoleAssignmentService.java:184-193 via TeamMembershipService.java:71-72: no roles, no default profile).
     * No production code writes `Membership.Status.ERASED`, so an erased membership is not a fixture here.
     */
    static Membership membership(Set<Role> roles, Membership.Status status, long version) {
        return new Membership("membership-1", "acc-1", CLUB, "member-1", roles, status, null, false, null,
                NOW.minusSeconds(86400), NOW.minusSeconds(3600), null, version, NOW.minusSeconds(3600), null, null);
    }

    // --- accessEmail ---------------------------------------------------------------------------------------------------------

    @Test void E11_T06_theAccessEmailOfAnActiveAccountWithAnActiveMembershipIsItsAddress() {
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account()));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership(Set.of(Role.MEMBER), Membership.Status.ACTIVE, 1)));

        assertThat(service.accessEmail("acc-1")).isEqualTo("laura@example.test");
    }

    @Test void E11_T06_aSuspendedMembershipHasNoAccessEmail() {
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account()));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership(Set.of(), Membership.Status.SUSPENDED, 2)));

        assertThatThrownBy(() -> service.accessEmail("acc-1")).isInstanceOf(ApiException.class).hasMessage("MEMBER_NOT_ACTIVE");
    }

    // --- limitResend ---------------------------------------------------------------------------------------------------------

    @Test void E11_T06_theFirstResendIsNotLimited() {
        assertThatCode(() -> service.limitResend("laura@example.test", "203.0.113.5")).doesNotThrowAnyException();
    }

    @Test void E11_T06_theEleventhResendToTheSameEmailWithinTheHourIsRateLimited() {
        for (int i = 0; i < 10; i++) { service.limitResend("laura@example.test", "203.0.113.5"); }

        assertThatThrownBy(() -> service.limitResend("laura@example.test", "203.0.113.5"))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure).hasMessage("RATE_LIMITED");
                    assertThat(failure.details()).containsEntry("retryAfter", 3600L);
                });
    }

    @Test void E11_T06_eachClientIpHasItsOwnResendBucket() {
        for (int i = 0; i < 60; i++) { service.limitResend("member" + i + "@example.test", "203.0.113.5"); }

        assertThatCode(() -> service.limitResend("marc@example.test", "198.51.100.7")).doesNotThrowAnyException();
    }

    // --- sendAccess ----------------------------------------------------------------------------------------------------------

    @Test void E11_T06_theAccessResendSendsAnAccessResendLinkOnTheClubAppHost() {
        when(accounts.findById("acc-1")).thenReturn(Optional.of(account()));
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership(Set.of(Role.MEMBER), Membership.Status.ACTIVE, 1)));
        when(clubs.appHost()).thenReturn("club-a.example.test");

        service.sendAccess("event-1", "acc-1");

        verify(links).createAndSend("laura@example.test", MagicLinkToken.Purpose.ACCESS_RESEND, "clubs-app", null, "club-a.example.test",
                null, null, "event-1");
    }

    // --- reactivate ----------------------------------------------------------------------------------------------------------

    @Test void E11_T06_reactivatingASuspendedMembershipRestoresItActiveAsMemberWithTheNextVersion() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership(Set.of(), Membership.Status.SUSPENDED, 4)));

        service.reactivate("member-1", "acc-1");

        var saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).saveTeam(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(Membership.Status.ACTIVE);
        assertThat(saved.getValue().roles()).containsExactly(Role.MEMBER);
        assertThat(saved.getValue().defaultProfile()).isEqualTo(Role.MEMBER);
        assertThat(saved.getValue().version()).isEqualTo(5);
        assertThat(saved.getValue().id()).isEqualTo("membership-1");
    }

    @Test void E11_T06_reactivatingAnAlreadyActiveMembershipChangesNothing() {
        when(memberships.findByAccountId("acc-1")).thenReturn(Optional.of(membership(Set.of(Role.MEMBER), Membership.Status.ACTIVE, 1)));

        service.reactivate("member-1", "acc-1");

        verify(memberships, never()).saveTeam(any());
    }
}
