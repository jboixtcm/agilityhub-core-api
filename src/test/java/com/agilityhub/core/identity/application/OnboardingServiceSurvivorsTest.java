package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.application.MemberOnboardingAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link OnboardingService} (S01 R-01-15, A4/A12; T-01-26): a phone of exactly 50 characters is valid,
 * and postponing one club's new policy keeps the account's postponements of another club's policy. Collaborators are mocks;
 * fictional data.
 */
class OnboardingServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final String PLATFORM_VERSION = "2026-01";

    final IdentityService identities = mock(IdentityService.class);
    final AccountService profiles = mock(AccountService.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final MemberOnboardingAccess members = mock(MemberOnboardingAccess.class);
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final ParameterCatalog catalog = mock(ParameterCatalog.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final MockEnvironment environment = new MockEnvironment()
            .withProperty("agilityhub.legal.privacyPolicyVersion", PLATFORM_VERSION)
            .withProperty("agilityhub.legal.privacyPolicyUrl", "https://agilityhub.example.test/privacy");
    final OnboardingService onboarding = new OnboardingService(identities, profiles, accounts, members, clubs, catalog, settings, environment,
            Clock.fixed(NOW, ZoneOffset.UTC));
    // A migrated member's membership (MigrationIdentityService.java:33-36): MEMBER default, not remembered, with its `createdAt`.
    final Membership membership = new Membership("mem-1", "acc-1", "club-a", "member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER,
            false, null, NOW.minus(Duration.ofDays(400)), null);

    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        TenantContext.clear();
        tenant = TenantContext.open("club-a");
        when(clubs.privacyPolicy("club-a")).thenReturn(new ClubConfigService.PrivacyPolicy("v3", "https://club-a.example.test/privacy"));
        var config = mock(ClubConfig.class);
        when(clubs.get("club-a")).thenReturn(config);
        when(config.get("signup.onboardingFields", Object.class)).thenReturn(List.of());
        when(settings.integer("legal.maxPostpones")).thenReturn(3);
    }

    @AfterEach void clearTenant() { tenant.close(); TenantContext.clear(); }

    static Account account(boolean pending, List<Account.Consent> consents, List<Account.ConsentPostponement> postponements) {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), pending, NOW.minus(Duration.ofDays(400)), null, null, Account.Source.MIGRATION,
                null, null, consents, postponements);
    }

    @Test void T_01_26_aPhoneOfExactly50CharactersIsSavedOnTheMember() {
        var account = account(true, List.of(new Account.Consent(Account.ConsentPolicy.PLATFORM, null, PLATFORM_VERSION, NOW.minus(Duration.ofDays(30)))), List.of());
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(account, membership));
        String phone = "+34 " + "6".repeat(46);
        assertThat(phone).hasSize(50);

        onboarding.complete("acc-1", true, "v3", "Laura Example", "ca", phone, true);

        verify(members).update("member-1", "acc-1", phone, true, "v3", NOW);
        verify(accounts).completeOnboarding("acc-1", new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "v3", NOW));
    }

    @Test void T_01_26_aPhoneOf51CharactersIsAValidationError() {
        var account = account(true, List.of(new Account.Consent(Account.ConsentPolicy.PLATFORM, null, PLATFORM_VERSION, NOW.minus(Duration.ofDays(30)))), List.of());
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(account, membership));

        assertThatThrownBy(() -> onboarding.complete("acc-1", true, "v3", "Laura Example", "ca", "+34 " + "6".repeat(47), true))
                .isInstanceOf(ApiException.class).hasMessage("VALIDATION_ERROR");
        verify(members, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test void T_01_26_postponingThisClubsNewPolicyKeepsThePostponementOfAnotherClubsPolicy() {
        // A global account with memberships in two clubs: it already postponed club-b's v2 on club-b's host.
        var otherClub = new Account.ConsentPostponement(Account.ConsentPolicy.CLUB, "club-b", "v2", 1);
        var account = account(false, List.of(
                new Account.Consent(Account.ConsentPolicy.PLATFORM, null, PLATFORM_VERSION, NOW.minus(Duration.ofDays(200))),
                new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "v2", NOW.minus(Duration.ofDays(200))),
                new Account.Consent(Account.ConsentPolicy.CLUB, "club-b", "v1", NOW.minus(Duration.ofDays(200)))), List.of(otherClub));
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(account, membership));

        onboarding.postpone("acc-1");

        verify(accounts).postponeConsent("acc-1", List.of(otherClub,
                new Account.ConsentPostponement(Account.ConsentPolicy.CLUB, "club-a", "v3", 1)));
    }
}
