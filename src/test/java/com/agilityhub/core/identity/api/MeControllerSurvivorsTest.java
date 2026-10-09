package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.AccountService;
import com.agilityhub.core.identity.application.IdentityService;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.identity.application.OnboardingService;
import com.agilityhub.core.identity.application.PasswordService;
import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.MemberIdentityAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import static com.agilityhub.core.identity.api.IdentityRequests.PasswordRequest;
import static com.agilityhub.core.identity.api.IdentityResponses.PlatformRole;
import static com.agilityhub.core.identity.api.IdentityResponses.Profile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link MeController} (S01 R-01-05, R-01-09, R-01-15). Every authenticated route runs behind
 * `CurrentUserFilter` (SecurityConfiguration:111), which always opens a {@link CurrentUser} for the bearer
 * (CurrentUserFilter:39): each club-scoped test opens the one the filter opens for its token.
 */
class MeControllerSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final IdentityService identities = mock(IdentityService.class);
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final PasswordService passwords = mock(PasswordService.class);
    final MemberIdentityAccess census = mock(MemberIdentityAccess.class);
    final MeController controller = new MeController(identities, clubs, mock(AccountService.class), passwords, mock(TokenService.class),
            mock(OnboardingService.class), mock(IdentityTransactions.class), mock(RefreshCookies.class), census);

    @BeforeEach void reset() { TenantContext.clear(); }
    @AfterEach void clear() { TenantContext.clear(); }

    /** As AccountService.java:37-38 creates it: ACTIVE, with `passwordChangedAt` only when it has a password. */
    static Account account(String id, String email, String name, String passwordHash, Set<Account.PlatformRole> platformRoles) {
        return new Account(id, email, name, "ca", passwordHash, platformRoles, Account.Status.ACTIVE,
                new Account.Security(0, null, passwordHash == null ? null : NOW, 0), Map.of(), false, NOW);
    }

    /** A stored membership carries its `createdAt` (SignupIdentityService.java:39, MembershipService.java:62). */
    static Membership membership(String id, String accountId, String memberId, Set<Role> roles) {
        return new Membership(id, accountId, CLUB, memberId, roles, Membership.Status.ACTIVE, null, false, null,
                NOW.minusSeconds(86_400 * 30L), NOW.minusSeconds(3_600));
    }

    static Jwt jwt(String subject, Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("eyJ.test.sig").header("alg", "RS256").subject(subject).claim("azp", "clubs-app");
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test void T_01_25_theGlobalMeListsThePlatformRolesOfTheAccount() {
        // R-01-15 on the ID host (no club context): the platform admin's bootstrap names its platform role.
        var admin = account("acc-platform", "platform@example.test", "Pat Example", "$argon2id$v=19$fictional", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN));
        when(identities.current("acc-platform")).thenReturn(new IdentityService.Session(admin, null));

        var me = controller.me(jwt("acc-platform", Map.of()));

        assertThat(me.account().platformRoles()).containsExactly(PlatformRole.AGILITYHUB_ADMIN);
        assertThat(me.membership()).isNull();
    }

    @Test void T_01_11_theImpersonatedViewNamesTheMemberAndKeepsTheAccountPasswordFlag() {
        // R-01-09: the grant's JWT is for the member's own account (ImpersonationService:68,116), and the filter opened the
        // impersonation that ImpersonationService.validate returned (CurrentUserFilter:35,39).
        TenantContext.open(CLUB);
        var member = account("acc-member", "laura@example.test", "Family Example", "$argon2id$v=19$fictional", Set.of());
        var membership = membership("ms-1", "acc-member", "member-1", Set.of(Role.MEMBER));
        when(identities.current("acc-member")).thenReturn(new IdentityService.Session(member, membership));
        when(census.bootstrap("member-1")).thenReturn(new MemberIdentityAccess.Bootstrap("FEMALE", null, null));
        when(census.displayName("member-1")).thenReturn(Optional.of("Laura Example Test"));
        when(clubs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(), null, Map.of()));
        // The grant's JWT claims (ImpersonationService.java:68-72): ROLE_MEMBER only and `memberId` = the impersonated member.
        var token = jwt("acc-member", Map.of("imp", true, "actorAccountId", "acc-admin", "impersonatedMemberId", "member-1", "clubId", CLUB,
                "roles", java.util.List.of("MEMBER"), "activeProfile", "MEMBER", "memberId", "member-1", "name", "Family Example"));

        MeResponse me;
        try (var scope = CurrentUser.open(new CurrentUser("acc-member", "Family Example",
                new CurrentUser.Impersonation("acc-admin", "Anna Admin", "member-1"), DomainEvent.Origin.BACKOFFICE))) {
            me = controller.me(token);
        }

        assertThat(me.impersonation()).isEqualTo(new MeResponse.Impersonation("Anna Admin", "Laura Example Test"));
        assertThat(me.account().hasPassword()).isTrue();
        assertThat(me.account().platformRoles()).isEmpty();
        assertThat(me.membership().roles()).containsExactly(Profile.MEMBER);
        assertThat(me.membership().gender()).isEqualTo(MeResponse.Gender.FEMALE);
    }

    @Test void T_01_25_aNonImpersonatedClubBootstrapHasNoImpersonationBanner() {
        TenantContext.open(CLUB);
        var admin = account("acc-admin", "anna@example.test", "Anna Admin", null, Set.of());
        var membership = membership("ms-2", "acc-admin", "member-2", Set.of(Role.MEMBER, Role.ADMIN));
        when(identities.current("acc-admin")).thenReturn(new IdentityService.Session(admin, membership));
        when(census.bootstrap("member-2")).thenReturn(new MemberIdentityAccess.Bootstrap(null, null, null));
        when(clubs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(), null, Map.of()));

        MeResponse me;
        try (var scope = CurrentUser.open(new CurrentUser("acc-admin", "Anna Admin", null, DomainEvent.Origin.BACKOFFICE))) {
            // TokenService.java:216-225: a club access token's claims for this membership.
            me = controller.me(jwt("acc-admin", Map.of("sid", "family-2", "activeProfile", "ADMIN", "clubId", CLUB,
                    "roles", java.util.List.of("ADMIN", "MEMBER"), "memberId", "member-2", "name", "Anna Admin")));
        }

        assertThat(me.impersonation()).isNull();
        assertThat(me.account().hasPassword()).isFalse();
        assertThat(me.membership().activeProfile()).isEqualTo(Profile.ADMIN);
        assertThat(me.membership().profiles()).containsExactly(Profile.MEMBER, Profile.ADMIN);
    }

    @Test void T_01_09_aPasswordChangeAnswers200() {
        var response = controller.password(new PasswordRequest(null, "Fictional-passphrase-26", "Fictional-passphrase-26"), jwt("acc-1", Map.of("sid", "family-1")));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(passwords).change("acc-1", "clubs-app", "family-1", null, "Fictional-passphrase-26", "Fictional-passphrase-26");
    }
}
