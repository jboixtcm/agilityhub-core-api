package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.HandoffCode;
import com.agilityhub.core.identity.persistence.HandoffCodeRepository;
import com.agilityhub.core.identity.persistence.MagicLinkTokenRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.identity.persistence.RefreshTokenRepository;
import com.agilityhub.core.platform.application.ClubAppUrls;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FE-03 (S01 R-01-13, owner decision of 13 Nov 2026): a handoff code of an account, redeemed with the host's refresh cookie of a live
 * session of another account, is refused with 409 HANDOFF_SESSION_CONFLICT before it is consumed; the same account's session, no
 * cookie or a dead one go on as before, and an impersonation launch code is never checked. Mocks; fictional ids.
 */
class HandoffSessionConflictTest {
    static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    static final String COOKIE = "fictional-refresh-cookie-value";

    final HandoffCodeRepository codes = mock(HandoffCodeRepository.class);
    final IdentityService identities = mock(IdentityService.class);
    final TokenService tokens = mock(TokenService.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final ImpersonationService impersonations = mock(ImpersonationService.class);
    final HandoffService service = new HandoffService(codes, identities, tokens, transactions, mock(ClubAppUrls.class), mock(SecurityEvents.class),
            Clock.fixed(NOW, ZoneOffset.UTC), impersonations);
    final Account codeAccount = account("account-b", Set.of());
    final TokenService.Tokens issued = new TokenService.Tokens(Jwt.withTokenValue("access-example").header("alg", "RS256").subject("account-b")
            .issuedAt(NOW).expiresAt(NOW.plusSeconds(900)).build(), null);

    static Account account(String id, Set<Account.PlatformRole> roles) {
        return new Account(id, id + "@example.test", "Example " + id, "ca", null, roles, Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW.minus(Duration.ofDays(30)));
    }
    static HandoffCode code(String source) {
        return new HandoffCode("code-1", "club-a", "account-b", "clubs-app", TokenService.digest("handoff-example"), source, "family-b", 0,
                NOW.plusSeconds(30), null);
    }
    static RefreshToken source() {
        return new RefreshToken("rt-b", TokenService.digest("source-example"), "account-b", "club-a", "clubs-admin", "family-b", 0, NOW.minusSeconds(600),
                NOW.plus(Duration.ofHours(1)), NOW.minusSeconds(60), null, null, Role.ADMIN, "Firefox / Linux", RefreshToken.Status.ACTIVE,
                Set.of(), false, null, NOW.minusSeconds(600), null);
    }

    @BeforeEach void setUp() {
        TenantContext.clear();
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(identities.current("account-b")).thenReturn(new IdentityService.Session(codeAccount,
                new Membership("membership-b", "account-b", "club-a", "member-b", Set.of(Role.MEMBER, Role.ADMIN), Membership.Status.ACTIVE, Role.MEMBER)));
        when(tokens.requireFamily("account-b", "clubs-admin", "family-b", 0)).thenReturn(source());
        when(codes.consume(eq("code-1"), any())).thenReturn(true);
        when(tokens.handoff(any(), eq("clubs-app"), any(), any())).thenReturn(issued);
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }
    void resolves(HandoffCode code) { when(codes.resolve(TokenService.digest("handoff-example"), "clubs-app")).thenReturn(Optional.of(code)); }

    @Test void FE_03_anotherAccountsLiveSessionOnTheHostRefusesTheCodeBeforeItIsConsumed() {
        resolves(code("clubs-admin"));
        when(tokens.liveSessionAccount(COOKIE, "clubs-app")).thenReturn("account-a");
        try (var scope = TenantContext.open("club-a")) {
            assertThatThrownBy(() -> service.exchange("handoff-example", "clubs-app", null, false, COOKIE))
                    .isInstanceOfSatisfying(ApiException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(ErrorCode.HANDOFF_SESSION_CONFLICT);
                        assertThat(failure.code().httpStatus()).isEqualTo(409);
                        assertThat(failure.details()).isEmpty();
                    });
        }
        verify(codes, never()).consume(any(), any());
        verify(tokens, never()).handoff(any(), any(), any(), any());
    }

    @Test void FE_03_theSameAccountNoCookieOrADeadOneRedeemAsBefore() {
        resolves(code("clubs-admin"));
        when(tokens.liveSessionAccount(COOKIE, "clubs-app")).thenReturn("account-b");
        try (var scope = TenantContext.open("club-a")) {
            assertThat(service.exchange("handoff-example", "clubs-app", null, false, COOKIE)).isSameAs(issued);
            // No cookie, or a cookie whose session is dead or unknown: liveSessionAccount answers null.
            when(tokens.liveSessionAccount(any(), eq("clubs-app"))).thenReturn(null);
            assertThat(service.exchange("handoff-example", "clubs-app", null, false, null)).isSameAs(issued);
            assertThat(service.exchange("handoff-example", "clubs-app", null, false, COOKIE)).isSameAs(issued);
        }
        verify(codes, times(3)).consume(eq("code-1"), any());
    }

    @Test void FE_03_anImpersonationLaunchCodeNeverLooksAtTheHostsCookie() {
        resolves(code(ImpersonationService.HANDOFF_SOURCE));
        when(tokens.liveSessionAccount(any(), any())).thenReturn("account-a");
        var jwt = Jwt.withTokenValue("impersonation-example").header("alg", "RS256").subject("account-c").issuedAt(NOW).expiresAt(NOW.plusSeconds(900)).build();
        when(impersonations.redeem(any())).thenReturn(Optional.of(jwt));
        try (var scope = TenantContext.open("club-a")) {
            assertThat(service.exchange("handoff-example", "clubs-app", null, false, COOKIE).access()).isSameAs(jwt);
        }
        verify(tokens, never()).liveSessionAccount(any(), any());
    }

    @Test void FE_03_theConsolesProductHostRefusesItToo() {
        var console = new HandoffCode("code-1", "club-a", "account-b", "clubs-admin", TokenService.digest("handoff-example"), "clubs-app", "family-b", 0,
                NOW.plusSeconds(30), null);
        when(codes.resolve(TokenService.digest("handoff-example"), "clubs-admin")).thenReturn(Optional.of(console));
        when(tokens.requireFamily("account-b", "clubs-app", "family-b", 0)).thenReturn(source());
        when(tokens.liveSessionAccount(COOKIE, "clubs-admin")).thenReturn("account-platform");
        assertThatThrownBy(() -> service.exchange("handoff-example", "clubs-admin", null, true, COOKIE))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.HANDOFF_SESSION_CONFLICT));
        verify(codes, never()).consume(any(), any());
        verify(tokens, never()).handoff(any(), any(), any(), any());
    }

    /** {@link TokenService#liveSessionAccount}: the account of a live session of that client in the current context, or null. */
    static class LiveSession {
        final AccountRepository accounts = mock(AccountRepository.class);
        final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        final TokenService real = new TokenService(mock(IdentityService.class), accounts, mock(MembershipRepository.class), refreshTokens,
                mock(AccountSessionRepository.class), mock(MagicLinkTokenRepository.class), mock(AuthSettings.class), mock(JwtEncoder.class),
                Clock.fixed(NOW, ZoneOffset.UTC), mock(IdentityTransactions.class), "https://id.example.test", mock(SecurityEvents.class), mock(EventPublisher.class));
        RefreshToken token(String client, long version, Instant createdAt) {
            return new RefreshToken("rt-a", TokenService.digest(COOKIE), "account-a", "club-a", client, "family-a", version, createdAt,
                    NOW.plus(Duration.ofDays(10)), NOW.minusSeconds(60), null, null, Role.MEMBER, "Firefox / Linux", RefreshToken.Status.ACTIVE,
                    Set.of(), false, null, createdAt, null);
        }
        void stored(RefreshToken presented, RefreshToken active, long accountVersion) {
            when(refreshTokens.find(TokenService.digest(COOKIE), presented.clientId())).thenReturn(Optional.of(presented));
            when(refreshTokens.activeFamily("account-a", "family-a", NOW)).thenReturn(Optional.ofNullable(active));
            when(accounts.findById("account-a")).thenReturn(Optional.of(new Account("account-a", "a@example.test", "Example A", "ca", null, Set.of(),
                    Account.Status.ACTIVE, new Account.Security(0, null, null, accountVersion), Map.of(), false, NOW.minus(Duration.ofDays(30)))));
        }
    }

    @Test void FE_03_aLiveSessionIsOneNotRevokedNotExpiredAndOfTheAccountsCurrentVersion() {
        var live = new LiveSession();
        var token = live.token("clubs-app", 2, NOW.minus(Duration.ofDays(3)));
        live.stored(token, token, 2);
        assertThat(live.real.liveSessionAccount(COOKIE, "clubs-app")).isEqualTo("account-a");
        assertThat(live.real.liveSessionAccount(null, "clubs-app")).isNull();
        // Another client's token is not this client's session; an unknown value is none.
        assertThat(live.real.liveSessionAccount(COOKIE, "clubs-admin")).isNull();
        assertThat(live.real.liveSessionAccount("unknown-value", "clubs-app")).isNull();

        var revoked = new LiveSession();
        var gone = revoked.token("clubs-app", 2, NOW.minus(Duration.ofDays(3)));
        revoked.stored(gone, null, 2);
        assertThat(revoked.real.liveSessionAccount(COOKIE, "clubs-app")).as("revoked or expired family").isNull();

        var older = new LiveSession();
        var before = older.token("clubs-app", 1, NOW.minus(Duration.ofDays(3)));
        older.stored(before, before, 2);
        assertThat(older.real.liveSessionAccount(COOKIE, "clubs-app")).as("revoked by a newer family version").isNull();

        var ended = new LiveSession();
        var backOffice = ended.token("clubs-admin", 0, NOW.minus(Duration.ofHours(12)));
        ended.stored(backOffice, backOffice, 0);
        assertThat(ended.real.liveSessionAccount(COOKIE, "clubs-admin")).as("a back-office session past its 12 hours").isNull();

        var missing = new LiveSession();
        when(missing.refreshTokens.find(TokenService.digest(COOKIE), "clubs-app")).thenReturn(Optional.of(token));
        when(missing.accounts.findById("account-a")).thenReturn(Optional.empty());
        assertThat(missing.real.liveSessionAccount(COOKIE, "clubs-app")).as("an account that no longer exists").isNull();
    }
}
