package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AUTH-01 (S01 R-01-06, owner decision of 13 Nov 2026): a back-office session (`clubs-admin`) ends 12 hours after it began, however
 * often it is refreshed; its refresh token and access tokens never outlive that end, and a session past it is neither renewed,
 * nor used (handoff, profile, password change), nor listed. Other clients keep `auth.sessionDays`. Mocks; fictional data.
 */
class BackOfficeSessionTest {
    static final Instant NOW = Instant.parse("2026-11-13T20:00:00Z");
    final IdentityService identities = mock(IdentityService.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    final AccountSessionRepository accountSessions = mock(AccountSessionRepository.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final JwtEncoder encoder = mock(JwtEncoder.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final TokenService tokens = new TokenService(identities, accounts, memberships, refreshTokens, accountSessions, mock(MagicLinkTokenRepository.class),
            settings, encoder, Clock.fixed(NOW, ZoneOffset.UTC), transactions, "https://id.example.test", mock(SecurityEvents.class), mock(EventPublisher.class));
    final Account account = new Account("acc-1", "admin@example.test", "Example Admin", "ca", null, Set.of(), Account.Status.ACTIVE,
            new Account.Security(0, null, null, 0), Map.of(), false, NOW.minus(Duration.ofDays(90)));
    final Membership membership = new Membership("mem-1", "acc-1", "club-a", "member-1", Set.of(Role.ADMIN), Membership.Status.ACTIVE, Role.ADMIN);

    @BeforeEach void setUp() {
        TenantContext.clear();
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(settings.integer("auth.maxSessions")).thenReturn(10);
        when(settings.integer("auth.sessionDays")).thenReturn(30);
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(account, membership));
        when(encoder.encode(any(JwtEncoderParameters.class))).thenAnswer(call -> {
            var claims = call.<JwtEncoderParameters>getArgument(0).getClaims();
            return Jwt.withTokenValue("access-example").header("alg", "RS256").subject("acc-1")
                    .issuedAt(claims.getIssuedAt()).expiresAt(claims.getExpiresAt()).build();
        });
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    RefreshToken row(String client, Instant createdAt, Instant expiresAt) {
        return new RefreshToken("rt-1", TokenService.digest("refresh-example"), "acc-1", "club-a", client, "fam-1", 0, createdAt, expiresAt,
                NOW.minusSeconds(60), null, null, Role.ADMIN, "Firefox / Linux", RefreshToken.Status.ACTIVE, Set.of(), false, null, createdAt, null);
    }
    RefreshToken stored(String client, Instant createdAt, Instant expiresAt) {
        var row = row(client, createdAt, expiresAt);
        when(refreshTokens.find(TokenService.digest("refresh-example"), client)).thenReturn(Optional.of(row));
        when(refreshTokens.rotate(eq("rt-1"), anyString(), eq(NOW))).thenReturn(true);
        return row;
    }
    RefreshToken inserted() {
        var captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).insert(captor.capture());
        return captor.getValue();
    }

    @Test void AUTH_01_theSessionEndIsTwelveHoursAfterABackOfficeSignInAndNoneForOtherClients() {
        assertThat(TokenService.BACK_OFFICE_SESSION).isEqualTo(Duration.ofHours(12));
        assertThat(TokenService.sessionEnd("clubs-admin", NOW)).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(TokenService.sessionEnd("clubs-app", NOW)).isNull();
        assertThat(TokenService.sessionEnd("id-web", NOW)).isNull();
        assertThat(TokenService.ended(row("clubs-admin", NOW.minus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(1))), NOW)).isTrue();
        assertThat(TokenService.ended(row("clubs-admin", NOW.minus(Duration.ofHours(12)).plusSeconds(1), NOW.plus(Duration.ofDays(1))), NOW)).isFalse();
        assertThat(TokenService.ended(row("clubs-app", NOW.minus(Duration.ofDays(20)), NOW.plus(Duration.ofDays(1))), NOW)).isFalse();
    }

    @Test void AUTH_01_aBackOfficeRefreshNeverSlidesPastTheSessionEndAndItsAccessTokenEndsWithIt() {
        Instant started = NOW.minus(Duration.ofHours(11).plusMinutes(50));
        stored("clubs-admin", started, started.plus(Duration.ofHours(12)));

        var issued = tokens.refresh("refresh-example", "clubs-admin");

        assertThat(inserted().expiresAt()).isEqualTo(started.plus(Duration.ofHours(12)));
        assertThat(issued.refresh().getExpiresAt()).isEqualTo(started.plus(Duration.ofHours(12)));
        assertThat(issued.access().getExpiresAt()).isEqualTo(started.plus(Duration.ofHours(12)));
    }

    @Test void AUTH_01_aMemberAppRefreshSlidesItsThirtyDaysAndKeepsAFifteenMinuteAccessToken() {
        stored("clubs-app", NOW.minus(Duration.ofDays(20)), NOW.plus(Duration.ofDays(10)));

        var issued = tokens.refresh("refresh-example", "clubs-app");

        assertThat(inserted().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(issued.access().getExpiresAt()).isEqualTo(NOW.plus(TokenService.ACCESS_TTL));
    }

    @Test void AUTH_01_aBackOfficeSessionPastItsTwelveHoursIsNotRenewedEvenWithALongerStoredExpiry() {
        stored("clubs-admin", NOW.minus(Duration.ofHours(12)), NOW.plus(Duration.ofDays(29)));

        assertThatThrownBy(() -> tokens.refresh("refresh-example", "clubs-admin")).isInstanceOf(ApiException.class).hasMessage("REFRESH_EXPIRED");
        verify(refreshTokens, never()).rotate(any(), any(), any());
        verify(refreshTokens, never()).insert(any());
    }

    @Test void AUTH_01_anEndedBackOfficeSessionCannotBeUsedAndIsNotListed() {
        var ended = row("clubs-admin", NOW.minus(Duration.ofHours(13)), NOW.plus(Duration.ofDays(29)));
        when(refreshTokens.activeFamily("acc-1", "fam-1", NOW)).thenReturn(Optional.of(ended));
        assertThatThrownBy(() -> tokens.requireFamily("acc-1", "clubs-admin", "fam-1", 0)).isInstanceOf(ApiException.class).hasMessage("UNAUTHENTICATED");

        var live = new RefreshToken("rt-2", "hash-2", "acc-1", "club-a", "clubs-app", "fam-2", 0, NOW.minus(Duration.ofDays(2)),
                NOW.plus(Duration.ofDays(28)), NOW.minusSeconds(5), null, null, Role.MEMBER, "Safari / iOS", RefreshToken.Status.ACTIVE);
        when(refreshTokens.sessions("acc-1", NOW, 0)).thenReturn(List.of(ended, live));
        try (var scope = TenantContext.open("club-a")) {
            assertThat(tokens.sessions("acc-1")).extracting(RefreshToken::familyId).containsExactly("fam-2");
        }
    }

    @Test void AUTH_01_aBackOfficeSignInGetsTwelveHoursAndAFifteenMinuteAccessToken() {
        var withPassword = new Account("acc-1", "admin@example.test", "Example Admin", "ca", "hash", Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW.minus(Duration.ofDays(90)));
        when(identities.authenticate("admin@example.test", "Example-password-12")).thenReturn(new IdentityService.Session(withPassword, membership));
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(withPassword, membership));
        when(accountSessions.active(eq("acc-1"), anyLong(), eq(NOW))).thenReturn(List.of());
        try (var scope = TenantContext.open("club-a")) {
            var issued = tokens.password("admin@example.test", "Example-password-12", "clubs-admin");
            assertThat(issued.refresh().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
            assertThat(issued.access().getExpiresAt()).isEqualTo(NOW.plus(TokenService.ACCESS_TTL));
        }
    }
}
