package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountMembershipRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.OidcState;
import com.agilityhub.core.identity.persistence.OidcStateRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantHostResolver;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link OidcService} (S01 R-01-11, T-01-13): `max_age`, the login redirect's `prompt`, the tenant check
 * and the live id-web family of a code exchange, the `memberships` claim, the logout without a browser session, the tenant scope's
 * closing, the session length and the redirect's redaction. Collaborators are mocks; fictional data.
 */
class OidcServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final String ISSUER = "https://id.example.test";
    static final String LOGIN = "https://id.example.test/login";
    static final String ID_WEB_CALLBACK = "https://id.example.test/oidc/callback";
    static final String CLUB_CALLBACK = "https://club-a.example.test/oidc/callback";
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String COOKIE = "browser-cookie-value-example";

    final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
    final OidcStateRepository state = mock(OidcStateRepository.class);
    final IdentityService identities = mock(IdentityService.class);
    final TokenService tokens = mock(TokenService.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final AccountMembershipRepository memberships = mock(AccountMembershipRepository.class);
    final AccountSessionRepository accountSessions = mock(AccountSessionRepository.class);
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final TenantHostResolver hosts = mock(TenantHostResolver.class);
    final JwtEncoder encoder = mock(JwtEncoder.class);
    final SigningKeys keys = mock(SigningKeys.class);
    final AuthSettings settings = mock(AuthSettings.class);
    final OidcService oidc = new OidcService(clients, state, identities, tokens, transactions, memberships, accountSessions, clubs, hosts,
            encoder, keys, Clock.fixed(NOW, ZoneOffset.UTC), settings, ISSUER, LOGIN);
    final Account account = new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
            new Account.Security(0, null, null, 0), Map.of(), false, NOW.minus(Duration.ofDays(90)));

    @BeforeEach void setUp() {
        TenantContext.clear();
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        // Like IdentityService.current: no membership without a tenant, the club's active membership inside club-a (as
        // SignupIdentityService.java:39 writes it, with its `createdAt`).
        when(identities.current("acc-1")).thenAnswer(call -> new IdentityService.Session(account, TenantContext.current() == null ? null
                : membership(Set.of(Role.MEMBER))));
        when(clients.findByClientId("id-web")).thenReturn(client("id-web", ID_WEB_CALLBACK, "https://id.example.test/"));
        when(clients.findByClientId("clubs-app")).thenReturn(client("clubs-app", CLUB_CALLBACK, "https://club-a.example.test/"));
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    static Membership membership(Set<Role> roles) {
        return new Membership("mem-1", "acc-1", "club-a", "member-1", roles, Membership.Status.ACTIVE, Role.MEMBER, false, null,
                NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(1)));
    }

    static RegisteredClient client(String id, String redirect, String logout) {
        return RegisteredClient.withId(id).clientId(id).clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirect).postLogoutRedirectUri(logout).scope("openid").scope("profile").scope("email").scope("memberships")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build()).build();
    }

    static OidcState.Request request(String clientId, String redirect, String prompt, Long maxAge, String clubId) {
        return new OidcState.Request(clientId, redirect, Set.of("openid"), "st-1", TokenService.digest(VERIFIER), "nonce-1", prompt,
                null, null, maxAge, clubId);
    }

    static RefreshToken family(String clientId, String familyId, String clubId, Instant authTime) {
        return new RefreshToken("rt-" + familyId, "hash-" + familyId, "acc-1", clubId, clientId, familyId, 0, authTime,
                NOW.plus(Duration.ofDays(30)), NOW, null, null, null, "Firefox / Linux", RefreshToken.Status.ACTIVE, Set.of("openid"), true,
                "nonce-1", authTime, null);
    }

    /** A live id-web browser session behind {@link #COOKIE}, authenticated `ageSeconds` ago. */
    private void browser(long ageSeconds) {
        var authTime = NOW.minusSeconds(ageSeconds);
        when(state.findById(TokenService.digest(COOKIE))).thenReturn(Optional.of(new OidcState.Browser(TokenService.digest(COOKIE), "acc-1",
                "fam-1", authTime, NOW.plus(Duration.ofDays(30)))));
        when(tokens.requireFamily("acc-1", "id-web", "fam-1", 0)).thenReturn(family("id-web", "fam-1", null, authTime));
    }

    @Test void T_01_13_aBrowserSessionYoungerThanMaxAgeGetsACodeWithoutANewLogin() {
        browser(100);

        var redirect = oidc.authorize(request("id-web", ID_WEB_CALLBACK, "", 3600L, null), COOKIE);

        assertThat(redirect.url()).startsWith(ID_WEB_CALLBACK + "?code=").contains("state=st-1");
        assertThat(redirect.cookie()).isNull();
        verify(state).insert(any(OidcState.Code.class));
        verify(state, never()).insert(any(OidcState.Flow.class));
    }

    @Test void T_01_13_aForcedLoginRedirectCarriesThePromptToTheLoginPage() {
        var redirect = oidc.authorize(request("id-web", ID_WEB_CALLBACK, "login", null, null), null);

        assertThat(redirect.url()).startsWith(LOGIN + "?flow=").contains("client_id=id-web").contains("prompt=login");
        assertThat(redirect.cookie()).isNotBlank();
    }

    @Test void T_01_13_aLoginRedirectWithoutPromptHasNoPromptParameter() {
        var redirect = oidc.authorize(request("id-web", ID_WEB_CALLBACK, "", null, null), null);

        assertThat(redirect.url()).startsWith(LOGIN + "?flow=").doesNotContain("prompt=");
    }

    @Test void T_01_13_issuingAClubCodeLeavesNoTenantOpenAfterwards() {
        browser(100);

        var redirect = oidc.authorize(request("clubs-app", CLUB_CALLBACK, "", null, "club-a"), COOKIE);

        assertThat(redirect.url()).startsWith(CLUB_CALLBACK + "?code=");
        assertThat(TenantContext.current()).isNull();
    }

    @Test void T_01_13_aClubClientWhoseRedirectHostIsNoClubIsAnUnknownHost() {
        when(hosts.resolve("club-a.example.test")).thenReturn(Optional.empty());
        var params = new HashMap<String, String>();
        params.put("client_id", "clubs-app"); params.put("response_type", "code"); params.put("redirect_uri", CLUB_CALLBACK);
        params.put("scope", "openid"); params.put("code_challenge", TokenService.digest(VERIFIER)); params.put("code_challenge_method", "S256");
        params.put("state", "st-1");

        assertThatThrownBy(() -> oidc.request(params)).isInstanceOf(ApiException.class).hasMessage("UNKNOWN_HOST");
    }

    private OidcState.Code code(String clientId, String redirect, String clubId) {
        var code = new OidcState.Code(TokenService.digest("code-value-example"), request(clientId, redirect, "", null, clubId), "acc-1",
                TokenService.digest(COOKIE), "fam-1", NOW.minusSeconds(30), NOW.plusSeconds(60));
        when(state.code(TokenService.digest("code-value-example"), clientId, NOW)).thenReturn(code);
        when(state.findById(TokenService.digest(COOKIE))).thenReturn(Optional.of(new OidcState.Browser(TokenService.digest(COOKIE), "acc-1",
                "fam-1", NOW.minusSeconds(30), NOW.plus(Duration.ofDays(30)))));
        when(state.consumeCode(code.id(), NOW)).thenReturn(true);
        return code;
    }

    private TokenService.Tokens issued() {
        var jwt = Jwt.withTokenValue("access-token-example").header("alg", "RS256").subject("acc-1").audience(List.of("clubs-app"))
                .issuedAt(NOW).expiresAt(NOW.plus(TokenService.ACCESS_TTL)).build();
        return new TokenService.Tokens(jwt, new OAuth2RefreshToken("refresh-example", NOW, NOW.plus(Duration.ofDays(30))), Set.of("openid"), true,
                "nonce-1", NOW.minusSeconds(30));
    }

    @Test void T_01_13_aClubCodeIsExchangedOnItsOwnClubHost() {
        var code = code("clubs-app", CLUB_CALLBACK, "club-a");
        when(accountSessions.active("acc-1", 0, NOW)).thenReturn(List.of(family("id-web", "fam-1", null, NOW.minusSeconds(30))));
        var tokensIssued = issued();
        when(tokens.authorizationCode(any(), eq("clubs-app"), eq("Firefox"), eq(Set.of("openid")), eq("nonce-1"), eq(code.authTime())))
                .thenReturn(tokensIssued);

        try (var scope = TenantContext.open("club-a")) {
            assertThat(oidc.exchange("code-value-example", "clubs-app", CLUB_CALLBACK, VERIFIER, "Firefox")).isSameAs(tokensIssued);
        }
    }

    @Test void T_01_13_aCodeWhoseIdWebFamilyIsNoLongerLiveIsAnInvalidGrant() {
        code("id-web", ID_WEB_CALLBACK, null);
        // The account is still signed in elsewhere (another browser and the club app), but the code's own id-web family is gone.
        when(accountSessions.active("acc-1", 0, NOW)).thenReturn(List.of(family("id-web", "fam-2", null, NOW.minusSeconds(600)),
                family("clubs-app", "fam-3", "club-a", NOW.minusSeconds(600))));
        when(tokens.authorizationCode(any(), any(), any(), any(), any(), any())).thenReturn(issued());

        assertThatThrownBy(() -> oidc.exchange("code-value-example", "id-web", ID_WEB_CALLBACK, VERIFIER, "Firefox"))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(failure.details()).containsEntry("oauth2Error", "invalid_grant");
                });
        verify(state, never()).consumeCode(any(), any());
    }

    @Test void T_01_13_theMembershipsClaimListsEachActiveClubWithItsNameAndRoles() {
        when(memberships.activeForAccount("acc-1")).thenReturn(List.of(membership(Set.of(Role.MEMBER, Role.INSTRUCTOR))));
        var config = mock(ClubConfig.class); var club = mock(ClubConfig.ClubView.class);
        when(clubs.get("club-a")).thenReturn(config); when(config.club()).thenReturn(club); when(club.name()).thenReturn("Club Example");

        var claims = oidc.claims("acc-1", Set.of("openid", "memberships"));

        assertThat(claims).containsEntry("sub", "acc-1");
        assertThat(claims.get("memberships")).isEqualTo(List.of(Map.of("clubId", "club-a", "clubName", "Club Example",
                "roles", List.of("INSTRUCTOR", "MEMBER"))));
    }

    /** One RSA-2048 key for the whole class: generating it is slow, and no test needs a fresh one. */
    private static RSAKey signingKey;

    private static synchronized RSAKey signingKey() throws Exception {
        if (signingKey == null) { signingKey = new RSAKeyGenerator(2048).keyID("kid-example").generate(); }
        return signingKey;
    }

    private String idToken(RSAKey key, String subject) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("id-web").claim("token_use", "id")
                .issueTime(Date.from(NOW.minusSeconds(60))).expirationTime(Date.from(NOW.plus(TokenService.ACCESS_TTL))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test void T_01_13_aLogoutWithoutBrowserSessionRedirectsWithItsState() throws Exception {
        var key = signingKey();
        when(keys.publicKeys()).thenReturn(new JWKSet(key.toPublicJWK()));

        assertThat(oidc.logout(idToken(key, "acc-1"), "https://id.example.test/", "st-1", null)).isEqualTo("https://id.example.test/?state=st-1");
        verify(state, never()).deleteBrowser(any());
    }

    @Test void T_01_13_aLogoutWhoseBrowserBelongsToAnotherAccountIsForbidden() throws Exception {
        var key = signingKey();
        when(keys.publicKeys()).thenReturn(new JWKSet(key.toPublicJWK()));
        browser(100);

        assertThatThrownBy(() -> oidc.logout(idToken(key, "acc-2"), "https://id.example.test/", "st-1", COOKIE))
                .isInstanceOf(ApiException.class).hasMessage("FORBIDDEN");
        verify(state, never()).deleteBrowser(any());
    }

    @Test void T_01_13_theBrowserSessionLastsTheConfiguredSessionDays() {
        when(settings.integer("auth.sessionDays")).thenReturn(30);

        assertThat(oidc.sessionSeconds()).isEqualTo(30L * 24 * 3600);
    }

    @Test void T_01_13_aRedirectNeverPrintsItsUrlOrCookie() {
        assertThat(new OidcService.Redirect(ID_WEB_CALLBACK + "?code=secret-code", COOKIE).toString()).isEqualTo("OidcRedirect[redacted]");
    }
}
