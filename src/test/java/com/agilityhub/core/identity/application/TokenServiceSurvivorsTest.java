package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.LoginLockout;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.identity.persistence.MagicLinkTokenRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.identity.persistence.RefreshTokenRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link TokenService} (S01 R-01-04, R-01-06, R-01-07; T-01-03, T-01-04, T-01-05, T-01-07, T-01-08,
 * T-01-22): the session-document touch of every grant, the membership's last access, a magic link or a refresh that loses its
 * race, the short forms of the password and refresh grants, the device summary and the redaction of the issued tokens.
 * Collaborators are mocks; fictional data.
 */
class TokenServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    // A Learn-imported bcrypt hash, the only bcrypt form the import accepts (LearnImportService.java:113).
    static final String HASH = "$2y$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";

    final IdentityService identities = mock(IdentityService.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    final AccountSessionRepository accountSessions = mock(AccountSessionRepository.class);
    final MagicLinkTokenRepository magicLinks = mock(MagicLinkTokenRepository.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final JwtEncoder encoder = mock(JwtEncoder.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final SecurityEvents securityEvents = mock(SecurityEvents.class);
    final EventPublisher events = mock(EventPublisher.class);
    final TokenService tokens = new TokenService(identities, accounts, memberships, refreshTokens, accountSessions, magicLinks, settings,
            encoder, Clock.fixed(NOW, ZoneOffset.UTC), transactions, "https://id.example.test", securityEvents, events);
    final Account account = new Account("acc-1", "laura@example.test", "Laura Example", "ca", HASH, Set.of(), Account.Status.ACTIVE,
            new Account.Security(0, null, NOW.minus(Duration.ofDays(30)), 0), Map.of(), false, NOW.minus(Duration.ofDays(90)));
    // A stored membership carries its `createdAt` (SignupIdentityService.java:39, MembershipService.java:62).
    final Membership membership = new Membership("mem-1", "acc-1", "club-a", "member-1", Set.of(Role.MEMBER, Role.INSTRUCTOR),
            Membership.Status.ACTIVE, Role.MEMBER, false, "ins-1", NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(1)));
    final Jwt jwt = Jwt.withTokenValue("access-token-example").header("alg", "RS256").subject("acc-1").audience(List.of("clubs-app"))
            .issuedAt(NOW).expiresAt(NOW.plus(TokenService.ACCESS_TTL)).build();

    @BeforeEach void setUp() {
        TenantContext.clear();
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(settings.integer("auth.maxSessions")).thenReturn(10);
        when(settings.integer("auth.sessionDays")).thenReturn(30);
        when(encoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    private IdentityService.Session session(boolean member) { return new IdentityService.Session(account, member ? membership : null); }

    private MagicLinkToken link() {
        // A global AgilityHub ID link (apps/id `/magic-link`): no club, so the exchange runs without a tenant.
        var token = new MagicLinkToken("ml-1", TokenService.digest("magic-value-example"), "acc-1", null, "id-web",
                MagicLinkToken.Purpose.LOGIN, null, NOW.minusSeconds(120), NOW.plusSeconds(780), null, "ip-hash-example", "Firefox / Linux");
        when(magicLinks.find(TokenService.digest("magic-value-example"), "id-web")).thenReturn(Optional.of(token));
        return token;
    }

    private RefreshToken refreshRow() {
        var old = new RefreshToken("rt-1", TokenService.digest("refresh-value-example"), "acc-1", null, "id-web", "fam-1", 0,
                NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(28)), NOW.minus(Duration.ofDays(1)), null, null, null,
                "Firefox / Linux", RefreshToken.Status.ACTIVE, Set.of("openid"), true, "nonce-1", NOW.minus(Duration.ofDays(2)), null);
        when(refreshTokens.find(TokenService.digest("refresh-value-example"), "id-web")).thenReturn(Optional.of(old));
        when(identities.current("acc-1")).thenReturn(session(false));
        return old;
    }

    @Test void T_01_07_theShortPasswordGrantIssuesTokens() {
        when(identities.authenticate("laura@example.test", "Correct-horse-1")).thenReturn(session(false));
        when(identities.current("acc-1")).thenReturn(session(false));

        var issued = tokens.password("laura@example.test", "Correct-horse-1", "id-web");

        assertThat(issued).isNotNull();
        assertThat(issued.access()).isSameAs(jwt);
        assertThat(issued.refresh().getTokenValue()).isNotBlank();
    }

    @Test void T_01_07_aClubLoginRecordsTheMembershipsLastAccess() {
        when(identities.authenticate("laura@example.test", "Correct-horse-1")).thenReturn(session(true));
        when(identities.current("acc-1")).thenReturn(session(true));

        try (var scope = TenantContext.open("club-a")) {
            tokens.password("laura@example.test", "Correct-horse-1", "clubs-app", "Firefox");
        }

        verify(memberships).accessed("acc-1", NOW);
    }

    @Test void T_01_08_aMagicLinkTouchesTheAccountSessionsBeforeConsumingTheLink() {
        var token = link();
        when(identities.current("acc-1")).thenReturn(session(false));
        when(magicLinks.consume("ml-1", NOW)).thenReturn(true);

        assertThat(tokens.magicLink("magic-value-example", "id-web", "Firefox")).isNotNull();

        InOrder order = inOrder(accounts, magicLinks);
        order.verify(accounts).touchSessions(token.accountId());
        order.verify(magicLinks).consume("ml-1", NOW);
        verify(accounts).verifyEmail("acc-1", NOW);
        verify(accounts).lockout("acc-1", LoginLockout.empty());
    }

    @Test void T_01_08_aMagicLinkConsumedByAConcurrentRequestIsInvalid() {
        link();
        when(identities.current("acc-1")).thenReturn(session(false));
        when(magicLinks.consume("ml-1", NOW)).thenReturn(false);

        assertThatThrownBy(() -> tokens.magicLink("magic-value-example", "id-web", "Firefox"))
                .isInstanceOf(ApiException.class).hasMessage("MAGIC_LINK_INVALID");
        verify(securityEvents).record(SecurityEvents.Type.MAGIC_LINK_INVALID, "acc-1", null);
        verify(accounts, never()).verifyEmail(any(), any());
    }

    @Test void T_01_04_theShortRefreshGrantRotatesAndIssuesTokens() {
        refreshRow();
        when(refreshTokens.rotate(eq("rt-1"), anyString(), eq(NOW))).thenReturn(true);

        var issued = tokens.refresh("refresh-value-example", "id-web");

        assertThat(issued).isNotNull();
        assertThat(issued.access()).isSameAs(jwt);
        assertThat(issued.scopes()).isEqualTo(Set.of("openid"));
    }

    @Test void T_01_04_aRefreshTouchesTheAccountSessions() {
        refreshRow();
        when(refreshTokens.rotate(eq("rt-1"), anyString(), eq(NOW))).thenReturn(true);

        tokens.refresh("refresh-value-example", "id-web", null);

        verify(accounts).touchSessions("acc-1");
    }

    @Test void T_01_04_aRefreshThatLosesItsRotationRaceRevokesTheFamilyAsAReuse() {
        refreshRow();
        when(refreshTokens.rotate(eq("rt-1"), anyString(), eq(NOW))).thenReturn(false);

        assertThatThrownBy(() -> tokens.refresh("refresh-value-example", "id-web", null))
                .isInstanceOf(ApiException.class).hasMessage("REFRESH_REUSED");
        verify(refreshTokens).revokeFamily("fam-1", NOW);
        verify(securityEvents).record(SecurityEvents.Type.REFRESH_TOKEN_REUSED, "acc-1", null);
        verify(refreshTokens, never()).insert(any());
    }

    @Test void T_01_05_switchingTheProfileTouchesTheAccountSessions() {
        when(identities.current("acc-1")).thenReturn(session(true));
        var family = new RefreshToken("rt-2", "hash-2", "acc-1", "club-a", "clubs-app", "fam-2", 0, NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(29)), NOW.minusSeconds(60), null, null, Role.MEMBER, "Firefox / Linux", RefreshToken.Status.ACTIVE,
                Set.of(), false, null, NOW.minus(Duration.ofDays(1)), null);
        when(refreshTokens.activeFamily("acc-1", "fam-2", NOW)).thenReturn(Optional.of(family));

        try (var scope = TenantContext.open("club-a")) {
            assertThat(tokens.profile("acc-1", "clubs-app", "fam-2", Role.INSTRUCTOR, false)).isSameAs(jwt);
        }

        verify(accounts).touchSessions("acc-1");
        verify(memberships).profile("acc-1", Role.INSTRUCTOR, false);
    }

    @Test void T_01_22_closingOneOfTheAccountsSessionsTouchesTheAccountSessions() {
        when(identities.current("acc-1")).thenReturn(session(false));
        var family = new RefreshToken("rt-3", "hash-3", "acc-1", null, "id-web", "fam-3", 0, NOW.minus(Duration.ofDays(3)),
                NOW.plus(Duration.ofDays(27)), NOW.minusSeconds(600), null, null, null, "Safari / iOS", RefreshToken.Status.ACTIVE,
                Set.of("openid"), true, null, NOW.minus(Duration.ofDays(3)), null);
        when(accountSessions.active("acc-1", 0, NOW)).thenReturn(List.of(family));

        tokens.revokeSession("acc-1", "fam-3");

        verify(accounts).touchSessions("acc-1");
        verify(accountSessions).revokeFamily("acc-1", "fam-3", NOW);
    }

    @Test void T_01_22_aSafariOnAnIphoneIsSummarisedAsSafariOnIos() {
        assertThat(TokenService.device("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/17.5 Mobile/15E148 Safari/604.1")).isEqualTo("Safari / iOS");
    }

    @Test void T_01_22_aSafariOnAMacIsSummarisedAsSafariOnMacos() {
        assertThat(TokenService.device("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/17.5 Safari/605.1.15")).isEqualTo("Safari / macOS");
    }

    @Test void T_01_22_aFirefoxOnLinuxIsSummarisedAsFirefoxOnLinux() {
        assertThat(TokenService.device("Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"))
                .isEqualTo("Firefox / Linux");
    }

    @Test void T_01_22_anUnknownClientIsSummarisedAsOtherBrowserOnOtherOs() {
        assertThat(TokenService.device("curl/8.7.1")).isEqualTo("Other browser / Other OS");
    }

    @Test void T_01_17_issuedTokensNeverPrintTheirValues() {
        var refresh = new OAuth2RefreshToken("refresh-value-example", NOW, NOW.plus(Duration.ofDays(30)));
        assertThat(new TokenService.Tokens(jwt, refresh).toString()).isEqualTo("Tokens[redacted]");
    }
}
