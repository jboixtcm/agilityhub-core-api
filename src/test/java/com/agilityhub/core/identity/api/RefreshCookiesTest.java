package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.TokenDelivery;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import static org.assertj.core.api.Assertions.*;

class RefreshCookiesTest {
    RefreshCookies cookies(String... profiles) {
        var environment = new MockEnvironment(); environment.setActiveProfiles(profiles);
        var client = RegisteredClient.withId("browser").clientId("browser").authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder().setting(TokenDelivery.SETTING, "COOKIE").build()).build();
        return new RefreshCookies(new InMemoryRegisteredClientRepository(client), environment);
    }
    @Test void T_01_27_secureRelaxationOnlyAppliesToLocalPlainHttp() {
        for (String profile : new String[]{"test", "prod", "staging", "local"}) {
            var request = new MockHttpServletRequest();
            assertThat(cookies(profile).clearHeader(request).contains("Secure")).isEqualTo(!profile.equals("local"));
            request.setSecure(true);
            assertThat(cookies(profile).clearHeader(request)).contains("Secure", "HttpOnly", "SameSite=Strict", "Max-Age=0");
        }
        assertThat(cookies("local", "prod").clearHeader(new MockHttpServletRequest())).contains("Secure");
        var response = new MockHttpServletResponse();
        cookies("test").issue(new MockHttpServletRequest(), response,
                new OAuth2RefreshToken("example", Instant.EPOCH, Instant.EPOCH.plusSeconds(86400)));
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=86400").doesNotContain("Domain=");
    }
    @Test void T_01_27_ambiguousEmptyAndMissingHostCookiesAreRejected() {
        var request = new MockHttpServletRequest();
        request.addParameter("client_id", "browser"); request.addHeader("Host", "example.test"); request.addHeader("Origin", "https://example.test");
        for (jakarta.servlet.http.Cookie[] values : new jakarta.servlet.http.Cookie[][] {
                {new jakarta.servlet.http.Cookie("other", "value")}, {new jakarta.servlet.http.Cookie(RefreshCookies.NAME, "")},
                {new jakarta.servlet.http.Cookie(RefreshCookies.NAME, "one"), new jakarta.servlet.http.Cookie(RefreshCookies.NAME, "two")}}) {
            request.setCookies(values);
            assertThatThrownBy(() -> cookies("test").read(request)).hasMessage("REFRESH_EXPIRED");
        }
        request.setCookies(new jakarta.servlet.http.Cookie(RefreshCookies.NAME, "one"));
        request.removeHeader("Host");
        assertThatThrownBy(() -> cookies("test").read(request)).hasMessage("REFRESH_EXPIRED");
    }
}
