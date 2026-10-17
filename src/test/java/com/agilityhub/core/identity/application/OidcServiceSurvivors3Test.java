package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountMembershipRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.OidcState;
import com.agilityhub.core.identity.persistence.OidcStateRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantHostResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor (session 10) of {@link OidcService#complete} (S01 R-01-11, T-01-13): completing the login of an OIDC flow
 * issues a new browser session and deletes the one behind the browser's previous cookie, so the old cookie no longer opens a
 * session (no session fixation). Collaborators are mocks; fictional data and fictional cookie/flow values.
 */
class OidcServiceSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final String ISSUER = "https://id.example.test";
    static final String ID_WEB_CALLBACK = "https://id.example.test/oidc/callback";
    static final String COOKIE = "previous-browser-cookie-example";
    static final String FLOW = "flow-value-example";

    final OidcStateRepository state = mock(OidcStateRepository.class);
    final IdentityService identities = mock(IdentityService.class);
    final TokenService tokens = mock(TokenService.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final OidcService oidc = new OidcService(mock(RegisteredClientRepository.class), state, identities, tokens, transactions,
            mock(AccountMembershipRepository.class), mock(AccountSessionRepository.class), mock(ClubConfigService.class),
            mock(TenantHostResolver.class), mock(JwtEncoder.class), mock(SigningKeys.class), Clock.fixed(NOW, ZoneOffset.UTC),
            mock(AuthSettings.class), ISSUER, ISSUER + "/login");
    final Account account = new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
            new Account.Security(0, null, null, 0), Map.of(), false, NOW.minus(Duration.ofDays(90)));

    @BeforeEach void setUp() {
        TenantContext.clear();
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        // On the ID host there is no tenant, so no membership (IdentityService.current).
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(account, null));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void T_01_13_completingTheLoginReplacesTheBrowsersPreviousSession() {
        var request = new OidcState.Request("id-web", ID_WEB_CALLBACK, Set.of("openid"), "st-1", TokenService.digest("verifier-example"),
                "nonce-1", "", null, null, null, null);
        var flow = new OidcState.Flow(TokenService.digest(FLOW), TokenService.digest(COOKIE), request, NOW.minusSeconds(60), NOW.plusSeconds(240));
        when(state.flow(TokenService.digest(FLOW), TokenService.digest(COOKIE), NOW)).thenReturn(flow);
        when(state.consumeFlow(flow.id(), flow.browserHash(), NOW)).thenReturn(true);
        // The id-web family the login UI's bearer belongs to, authenticated after the flow started.
        when(tokens.requireFamily("acc-1", "id-web", "fam-1", 0)).thenReturn(new RefreshToken("rt-1", "hash-1", "acc-1", null, "id-web",
                "fam-1", 0, NOW.minusSeconds(30), NOW.plus(Duration.ofDays(30)), NOW, null, null, null, "Firefox / Linux",
                RefreshToken.Status.ACTIVE, Set.of("openid"), true, null, NOW.minusSeconds(30), null));
        var bearer = Jwt.withTokenValue("eyJ.fictional.sig").header("alg", "RS256").subject("acc-1").audience(List.of("id-web"))
                .claim("sid", "fam-1").issuedAt(NOW.minusSeconds(30)).expiresAt(NOW.plusSeconds(870)).build();

        var redirect = oidc.complete(FLOW, COOKIE, bearer, ISSUER);

        assertThat(redirect.url()).startsWith(ID_WEB_CALLBACK + "?code=");
        assertThat(redirect.cookie()).isNotBlank().isNotEqualTo(COOKIE);
        verify(state).insert(any(OidcState.Browser.class));
        verify(state).deleteBrowser(TokenService.digest(COOKIE));
    }
}
