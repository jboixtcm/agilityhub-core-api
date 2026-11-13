package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.persistence.RefreshToken;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AUTH-01 (S01 R-01-06, owner decision of 13 Nov 2026): a back-office session (`clubs-admin`) ends 12 hours after its sign-in,
 * however often it is refreshed, and its access tokens never outlive it; the member app keeps its sliding `auth.sessionDays`.
 * Fictional accounts and hosts only.
 */
class BackOfficeSessionIT extends IdentityIntegrationSupport {
    MockHttpServletResponse signIn(String client) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", HOST).secure(true).contentType("application/x-www-form-urlencoded")
                .param("grant_type", "password").param("client_id", client).param("username", "admin@example.test").param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse();
    }
    org.springframework.test.web.servlet.ResultActions renew(String client, String value) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", HOST).header("Origin", "https://" + HOST).secure(true)
                .contentType("application/x-www-form-urlencoded").param("grant_type", "refresh_token").param("client_id", client)
                .cookie(new Cookie(RefreshCookies.NAME, value)));
    }
    /** The access token's own lifetime, `exp - iat` (the response's `expires_in` counts from the wall clock, not the test clock). */
    static long expiresIn(MockHttpServletResponse response) throws Exception {
        var claims = com.nimbusds.jwt.SignedJWT.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString())
                .path("access_token").asText()).getJWTClaimsSet();
        return Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()).toSeconds();
    }

    @Test void AUTH_01_aBackOfficeSessionEndsTwelveHoursAfterItsSignInHoweverOftenItIsRefreshed() throws Exception {
        var signedIn = signIn("clubs-admin");
        assertThat(signedIn.getHeader("Set-Cookie")).contains("Max-Age=43200");
        assertThat(expiresIn(signedIn)).isEqualTo(900);
        var started = clock.instant();
        assertThat(mongo.findAll(RefreshToken.class)).singleElement()
                .satisfies(token -> assertThat(token.expiresAt()).isEqualTo(started.plus(Duration.ofHours(12))));

        clock.advance(Duration.ofHours(6));
        var renewed = renew("clubs-admin", signedIn.getCookie(RefreshCookies.NAME).getValue()).andExpect(status().isOk()).andReturn().getResponse();
        // The refresh does not slide past the session's end: 6 hours are left.
        assertThat(renewed.getHeader("Set-Cookie")).contains("Max-Age=21600");
        clock.advance(Duration.ofHours(5).plusMinutes(50));
        renewed = renew("clubs-admin", renewed.getCookie(RefreshCookies.NAME).getValue()).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(renewed.getHeader("Set-Cookie")).contains("Max-Age=600");
        // The last access token ends with the session, not 15 minutes later.
        assertThat(expiresIn(renewed)).isEqualTo(600);
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(token -> !token.expiresAt().isAfter(started.plus(Duration.ofHours(12))));

        clock.advance(Duration.ofMinutes(10));
        renew("clubs-admin", renewed.getCookie(RefreshCookies.NAME).getValue())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
    }

    @Test void AUTH_01_theMemberAppKeepsItsSlidingThirtyDays() throws Exception {
        var signedIn = signIn("clubs-app");
        assertThat(signedIn.getHeader("Set-Cookie")).contains("Max-Age=2592000");
        clock.advance(Duration.ofHours(13));
        var renewed = renew("clubs-app", signedIn.getCookie(RefreshCookies.NAME).getValue()).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(renewed.getHeader("Set-Cookie")).contains("Max-Age=2592000");
        assertThat(expiresIn(renewed)).isEqualTo(900);
    }

    /** A back-office session issued before the cap (`expiresAt` 30 days after its last refresh) ends at its next refresh. */
    @Test void AUTH_01_aBackOfficeSessionIssuedBeforeTheCapEndsAtItsNextRefresh() throws Exception {
        String value = signIn("clubs-admin").getCookie(RefreshCookies.NAME).getValue();
        mongo.updateMulti(new Query(), new Update().set("createdAt", clock.instant().minus(Duration.ofHours(13)))
                .set("expiresAt", clock.instant().plus(Duration.ofDays(29))), RefreshToken.class);
        renew("clubs-admin", value).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        assertThat(mongo.find(Query.query(Criteria.where("replacedByHash").ne(null)), RefreshToken.class)).isEmpty();
    }
}
