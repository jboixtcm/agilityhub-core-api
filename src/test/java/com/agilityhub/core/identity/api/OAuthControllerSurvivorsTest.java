package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.ImpersonationService;
import com.agilityhub.core.identity.application.OidcService;
import com.agilityhub.core.identity.application.SigningKeys;
import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.persistence.OidcState;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import static com.agilityhub.core.identity.api.IdentityRequests.RevokeRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link OAuthController} (S01 R-01-10, R-01-11): `authorize` accepts parameters up to 2048
 * characters, the browser-session cookie is picked by its name among the host's cookies, and a revoked refresh token
 * answers 200 and clears `ah_refresh`.
 */
class OAuthControllerSurvivorsTest {
    final TokenService tokens = mock(TokenService.class);
    final ImpersonationService impersonations = mock(ImpersonationService.class);
    final OidcService oidc = mock(OidcService.class);
    // Real transport helper outside the local profile: the clear header is the production one.
    final RefreshCookies refreshCookies = new RefreshCookies(mock(RegisteredClientRepository.class), mock(Environment.class));
    final OAuthController controller = new OAuthController(tokens, impersonations, oidc, mock(SigningKeys.class), new ObjectMapper(), refreshCookies);

    /** What OidcService.request (OidcService.java:93-94) builds from {@link #authorizeRequest}'s parameters. */
    static OidcState.Request request(String state) {
        return new OidcState.Request("id-web", "https://id.example.test/callback", Set.of("openid"), state, "c".repeat(43), null, "",
                null, null, null, null);
    }

    static MockHttpServletRequest authorizeRequest(String state) {
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.addParameter("response_type", "code");
        request.addParameter("client_id", "id-web");
        request.addParameter("redirect_uri", "https://id.example.test/callback");
        request.addParameter("scope", "openid");
        request.addParameter("state", state);
        request.addParameter("code_challenge", "c".repeat(43));
        request.addParameter("code_challenge_method", "S256");
        return request;
    }

    static org.springframework.http.ResponseEntity<Void> authorize(OAuthController controller, MockHttpServletRequest request) {
        return controller.authorize("code", "id-web", "https://id.example.test/callback", "openid", request.getParameter("state"),
                "c".repeat(43), "S256", null, null, null, null, null, request);
    }

    @Test void T_01_13_anAuthorizationParameterOfExactly2048CharactersIsAccepted() {
        // OidcService.request (OidcService:65-95) puts no bound of its own on `state`: the 2048-character cap is the controller's.
        var state = "s".repeat(2048);
        when(oidc.request(anyMap())).thenReturn(request(state));
        // Without a browser session OidcService.authorize (:104-112) sends the login page and a new browser cookie.
        when(oidc.authorize(request(state), null)).thenReturn(new OidcService.Redirect(
                "https://id.example.test/login?flow=flow-value&client_id=id-web", "browser-value"));
        when(oidc.sessionSeconds()).thenReturn(30L * 86_400);

        var response = authorize(controller, authorizeRequest(state));

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst("Set-Cookie")).startsWith(OidcService.COOKIE + "=browser-value;");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(oidc).request(params.capture());
        assertThat(params.getValue()).containsEntry("state", state);
    }

    @Test void T_01_13_anAuthorizationParameterOver2048CharactersIsRejected() {
        assertThatThrownBy(() -> authorize(controller, authorizeRequest("s".repeat(2049))))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(oidc);
    }

    @Test void T_01_13_theBrowserSessionCookieIsPickedByNameAmongTheHostCookies() {
        var request = authorizeRequest("state");
        // A browser sends every cookie of the ID host; the provider's own cookie need not come first.
        request.setCookies(new Cookie("JSESSIONID", "container-session"), new Cookie(OidcService.COOKIE, "browser-value"));
        when(oidc.request(anyMap())).thenReturn(request("state"));
        // A live browser session gets a code without a new cookie (OidcService.java:100-101).
        when(oidc.authorize(any(), any())).thenReturn(new OidcService.Redirect("https://id.example.test/callback?code=c&state=state", null));

        authorize(controller, request);

        verify(oidc).authorize(request("state"), "browser-value");
    }

    @Test void E11_T06_revokingARefreshTokenAnswers200AndClearsTheRefreshCookie() {
        // R-01-10: a BODY client (`ar-app`, application.yml core.oidc.clients; `clubs-app` is COOKIE and never holds the token)
        // revokes its opaque refresh token; ImpersonationService.revoke is false for it (no JWT separator, :133).
        var jwt = Jwt.withTokenValue("eyJ.access.sig").header("alg", "RS256").subject("acc-1")
                .claim("azp", "ar-app").claim("sid", "family-1").build();
        var servlet = new MockHttpServletRequest("POST", "/oauth2/revoke");
        when(impersonations.revoke("acc-1", "opaque-refresh")).thenReturn(false);

        var response = controller.revoke(new RevokeRequest("opaque-refresh"), jwt, servlet);

        verify(tokens).revoke("acc-1", "opaque-refresh");
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Set-Cookie")).startsWith("ah_refresh=;").contains("Max-Age=0", "Path=/oauth2/token");
    }
}
