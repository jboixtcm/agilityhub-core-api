package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantHostResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class TenantFilterTest {
    final TenantHostResolver hosts = host -> switch (host == null ? "" : host) {
        case "a.example.test" -> Optional.of("club-a"); case "b.example.test" -> Optional.of("club-b"); default -> Optional.empty();
    };
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); }
    TenantFilter filter(boolean local) throws java.io.IOException {
        var messages = new com.agilityhub.core.shared.application.IcuMessageSource();
        var locales = new RequestLocaleResolver(messages, ignored -> java.util.Optional.empty());
        return new TenantFilter(hosts, local, new ApiExceptionHandler(messages, locales), new ObjectMapper(),
                org.mockito.Mockito.mock(com.agilityhub.core.shared.application.SecurityEvents.class));
    }
    MockHttpServletRequest request(String path, String host, String override) {
        var request = new MockHttpServletRequest("GET", path);
        if (host != null) { request.addHeader("Host", host); }
        if (override != null) { request.addHeader("X-Club-Host", override); }
        return request;
    }
    void jwt(String clubId) {
        var jwt = Jwt.withTokenValue("fictional").header("alg", "RS256").subject("account-a");
        if (clubId != null) { jwt.claim("clubId", clubId); }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(true);
    }
    @Test void T_02_06_hostAndLocalOverrideAreTrustedOnlyInLocal() throws Exception {
        for (String path : new String[]{"/api/v1/branding", "/api/v1/manifest.webmanifest", "/api/v1/public/info", "/oauth2/authorize"}) {
            var observed = new AtomicReference<String>(); var response = new MockHttpServletResponse();
            filter(true).doFilter(request(path, "b.example.test", "a.example.test"), response,
                    (req, res) -> observed.set(TenantContext.require()));
            assertThat(observed).hasValue("club-a"); assertThat(TenantContext.current()).isNull();
            filter(false).doFilter(request(path, "b.example.test", "a.example.test"), response,
                    (req, res) -> observed.set(TenantContext.require()));
            assertThat(observed).hasValue("club-b");
        }
        var response = new MockHttpServletResponse();
        filter(false).doFilter(request("/api/v1/branding", "unknown.example.test", "a.example.test"), response,
                (req, res) -> fail("Unknown host must not reach MVC"));
        assertThat(response.getStatus()).isEqualTo(404); assertThat(response.getContentAsString()).contains("UNKNOWN_HOST", "traceId");
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_02_06_jwtClaimWinsAndDifferentKnownHostIsForbidden() throws Exception {
        jwt("club-a");
        for (String host : new String[]{"a.example.test", "unknown.example.test", null}) {
            filter(false).doFilter(request("/api/v1/private", host, null), new MockHttpServletResponse(),
                    (req, res) -> assertThat(TenantContext.require()).isEqualTo("club-a"));
        }
        var response = new MockHttpServletResponse();
        filter(false).doFilter(request("/api/v1/branding", "b.example.test", null), response, (req, res) -> fail("Mismatch"));
        assertThat(response.getStatus()).isEqualTo(403); assertThat(response.getContentAsString()).contains("TENANT_MISMATCH");
        for (String claim : new String[]{null, " "}) {
            jwt(claim); response = new MockHttpServletResponse();
            filter(false).doFilter(request("/api/v1/branding", "a.example.test", null), response, (req, res) -> fail("Missing membership"));
            assertThat(response.getStatus()).isEqualTo(403); assertThat(response.getContentAsString()).contains("NO_MEMBERSHIP");
        }
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_02_06_globalPathsAndExceptionalExitsHaveNoTenantLeak() throws Exception {
        jwt("club-a");
        for (String path : new String[]{"/api/v1/health", "/api/v1/openapi.json", "/api/v1/platform", "/api/v1/platform/clubs"}) {
            filter(false).doFilter(request(path, "b.example.test", null), new MockHttpServletResponse(),
                    (req, res) -> assertThat(TenantContext.current()).isNull());
        }
        assertThatThrownBy(() -> filter(false).doFilter(request("/api/v1/branding", "a.example.test", null), new MockHttpServletResponse(),
                (req, res) -> { throw new IllegalStateException("Controller failed"); })).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.current()).isNull();
        SecurityContextHolder.clearContext();
        filter(false).doFilter(request("/api/v1/openapi.json", null, null), new MockHttpServletResponse(),
                (req, res) -> assertThat(TenantContext.current()).isNull());
    }
}
