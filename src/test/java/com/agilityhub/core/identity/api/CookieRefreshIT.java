package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.persistence.RefreshToken;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CookieRefreshIT extends IdentityIntegrationSupport {
    MockHttpServletRequestBuilder cookieGrant(String value) {
        return post("/oauth2/token").header("Host", HOST).contentType("application/x-www-form-urlencoded")
                .param("grant_type", "refresh_token").param("client_id", "clubs-app")
                .cookie(new Cookie(RefreshCookies.NAME, value));
    }
    @Test void T_01_27_browserClientsOmitBodyRefreshAndIssueExactHostOnlyCookie() throws Exception {
        for (String client : List.of("clubs-app", "clubs-admin", "id-web")) {
            String host = client.equals("id-web") ? "id.agilitydoghub.com" : HOST;
            var response = mvc.perform(post("/oauth2/token").header("Host", host).secure(true)
                    .contentType("application/x-www-form-urlencoded").param("grant_type", "password")
                    .param("client_id", client).param("username", "admin@example.test").param("password", PASSWORD))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").doesNotExist()).andReturn().getResponse();
            assertThat(response.getHeader("Set-Cookie")).contains("ah_refresh=", "HttpOnly", "Secure", "SameSite=Strict",
                    "Path=/oauth2/token", "Max-Age=2592000").doesNotContain("Domain=");
            assertThat(response.getCookie(RefreshCookies.NAME).getValue()).hasSize(43);
        }
    }
    @Test void T_01_27_cookieRequiresExplicitBrowserClientAndMatchingOriginOrReferer() throws Exception {
        String value = refreshValue(login());
        for (String origin : List.of("https://b.example.test", "https://evil.example.test", "null", "https://app.example.test.evil.test",
                "https://app.example.test@evil.test", "https://app.example.test/path", "https://[", "file://app.example.test")) {
            mvc.perform(cookieGrant(value).header("Origin", origin).header("Referer", "https://" + HOST + "/"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        }
        mvc.perform(cookieGrant(value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        for (String client : List.of("learn", "ar-app", "unknown")) {
            mvc.perform(post("/oauth2/token").header("Host", HOST).header("Origin", "https://" + HOST)
                    .param("grant_type", "refresh_token").param("client_id", client).cookie(new Cookie(RefreshCookies.NAME, value)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_EXPIRED"));
        }
        mvc.perform(post("/oauth2/token").header("Host", HOST).header("Origin", "https://" + HOST)
                .param("grant_type", "refresh_token").cookie(new Cookie(RefreshCookies.NAME, value)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/oauth2/token").header("Host", HOST).header("Origin", "https://" + HOST)
                .param("grant_type", "refresh_token").param("client_id", "clubs-app"))
                .andExpect(status().isBadRequest());
        mvc.perform(cookieGrant(value).header("Referer", "https://" + HOST + "/page?tab=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").doesNotExist());
    }
    @Test void T_01_04_cookieRotationSlidesAndReuseClearsRevokedFamily() throws Exception {
        String first = refreshValue(login());
        clock.advance(Duration.ofMinutes(2));
        var response = mvc.perform(cookieGrant(first).header("Origin", "https://" + HOST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").doesNotExist()).andReturn().getResponse();
        String next = response.getCookie(RefreshCookies.NAME).getValue();
        assertThat(next).isNotEqualTo(first);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=2592000");
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(token -> token.expiresAt().equals(clock.instant().plus(Duration.ofDays(30))));
        for (String value : List.of(first, next)) {
            mvc.perform(cookieGrant(value).header("Origin", "https://" + HOST)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(value.equals(first) ? "REFRESH_REUSED" : "REFRESH_EXPIRED"))
                    .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        }
        assertThat(mongo.findAll(RefreshToken.class)).allMatch(token -> token.status() == RefreshToken.Status.REVOKED);
    }
    @Test void T_01_27_revokeUsesBearerSessionWithoutReadingCookieAndDeleteClearsCurrentOnly() throws Exception {
        var login = login();
        String bearer = "Bearer " + login.path("access_token").asText();
        mvc.perform(post("/oauth2/revoke").header("Host", HOST).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth2/revoke").header("Host", "b.example.test").header("Authorization", bearer)
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        for (int n = 0; n < 2; n++) {
            mvc.perform(post("/oauth2/revoke").header("Host", HOST).header("Authorization", bearer)
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        }
        refresh(refreshValue(login), HOST, "clubs-app").andExpect(status().isBadRequest());
        var fresh = login();
        String access = fresh.path("access_token").asText();
        mvc.perform(delete("/api/v1/me/sessions/other").header("Host", HOST).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(delete("/api/v1/me/sessions/" + SignedJWT.parse(access).getJWTClaimsSet().getStringClaim("sid"))
                .header("Host", HOST).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
}
