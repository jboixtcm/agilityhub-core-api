package com.agilityhub.core.identity.api;

import com.agilityhub.core.platform.persistence.SecurityEvent;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "core.security.rate-limits.enabled=true")
class RateLimitIT extends IdentityIntegrationSupport {
    @Test void T_01_15_eleventhMagicLinkForNormalizedEmailIsLimitedAcrossIpsAndHosts() throws Exception {
        String body = "{\"email\":\"rate-email@example.test\",\"purpose\":\"LOGIN\",\"client_id\":\"clubs-app\"}";
        for (int n = 0; n < 10; n++) {
            mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json").content(body)
                    .with(request -> { request.setRemoteAddr("203.0.113.71"); return request; })).andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", "b.example.test").contentType("application/json")
                .content(body.replace("rate-email@", "RATE-EMAIL@"))
                .with(request -> { request.setRemoteAddr("203.0.113.72"); return request; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json")
                .content(body.replace("rate-email@", "another-email@"))
                .with(request -> { request.setRemoteAddr("203.0.113.72"); return request; })).andExpect(status().isAccepted());
        clock.advance(Duration.ofHours(1));
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json").content(body)
                .with(request -> { request.setRemoteAddr("203.0.113.72"); return request; })).andExpect(status().isAccepted());
    }

    @Test void T_01_15_sixtyFirstMagicLinkPerIpIsLimitedEvenWithDistinctEmails() throws Exception {
        for (int n = 0; n < 60; n++) {
            if (n > 0 && n % 20 == 0) { clock.advance(Duration.ofMinutes(1)); }
            mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json")
                    .content("{\"email\":\"ip-quota-" + n + "@example.test\",\"purpose\":\"LOGIN\",\"client_id\":\"clubs-app\"}")
                    .with(request -> { request.setRemoteAddr("203.0.113.73"); return request; })).andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json")
                .content("{\"email\":\"ip-quota-final@example.test\",\"purpose\":\"LOGIN\",\"client_id\":\"clubs-app\"}")
                .with(request -> { request.setRemoteAddr("203.0.113.73"); return request; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "3480"));
    }

    @Test void T_01_15_magicLinkUsesTheAuthenticationIpQuotaBeforeTenantResolution() throws Exception {
        String body = "{\"email\":\"member@example.test\",\"purpose\":\"LOGIN\",\"client_id\":\"clubs-app\"}";
        for (int index = 0; index < 30; index++) {
            mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json").content(body.replace("member@", "member" + index + "@"))
                    .with(request -> { request.setRemoteAddr("203.0.113.41"); return request; }))
                    .andExpect(status().isAccepted());
        }
        for (String path : new String[]{"/api/v1/auth/magic-link", "/oauth2/token"}) {
            mvc.perform(post(path).header("Host", "unknown.example.test").contentType("application/json").content(body)
                            .with(request -> { request.setRemoteAddr("203.0.113.41"); return request; }))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        }
        assertThat(mongo.findAll(SecurityEvent.class)).anySatisfy(event -> {
            assertThat(event.type().name()).isEqualTo("RATE_LIMITED");
            assertThat(event.ip()).isEqualTo("203.0.113.41");
        });
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", "id.example.test").contentType("application/json").content(body)
                        .with(request -> { request.setRemoteAddr("203.0.113.42"); return request; }))
                .andExpect(status().isAccepted());
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json").content(body)
                        .with(request -> { request.setRemoteAddr("203.0.113.41"); return request; }))
                .andExpect(status().isAccepted());
    }

    @Test void T_01_15_thirtyFirstTokenRequestIsLimitedBeforeTenantResolutionWithRetryAfterAndEvent() throws Exception {
        for (int index = 0; index < 30; index++) {
            mvc.perform(post("/oauth2/token").header("Host", HOST).with(request -> {
                request.setRemoteAddr("203.0.113.11"); return request;
            })).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/oauth2/token").header("Host", "unknown.example.test")
                        .header("Accept-Language", "en").with(request -> { request.setRemoteAddr("203.0.113.11"); return request; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.details").isMap())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(mongo.findAll(SecurityEvent.class)).anySatisfy(event -> {
            assertThat(event.type().name()).isEqualTo("RATE_LIMITED");
            assertThat(event.ip()).isEqualTo("203.0.113.11");
            assertThat(event.at()).isEqualTo(clock.instant());
            assertThat(event.route()).isEqualTo("/oauth2/token");
        });
        mvc.perform(post("/oauth2/token").header("Host", HOST).with(request -> {
            request.setRemoteAddr("203.0.113.12"); return request;
        })).andExpect(status().isBadRequest());
        clock.advance(Duration.ofSeconds(59));
        mvc.perform(post("/oauth2/token").header("Host", HOST).with(request -> {
            request.setRemoteAddr("203.0.113.11"); return request;
        })).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(post("/oauth2/token").header("Host", HOST).with(request -> {
            request.setRemoteAddr("203.0.113.11"); return request;
        })).andExpect(status().isBadRequest());
    }

    @Test void T_01_15_brandingAndPublicRoutesUseSeparateIpBucketsAndIgnoreSpoofedForwarding() throws Exception {
        for (int index = 0; index < 120; index++) {
            mvc.perform(get("/api/v1/branding").header("Host", HOST).with(request -> {
                request.setRemoteAddr("203.0.113.21"); return request;
            })).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/branding").header("Host", HOST).header("X-Forwarded-For", "203.0.113.99")
                .with(request -> { request.setRemoteAddr("203.0.113.21"); return request; }))
                .andExpect(status().isTooManyRequests());
        for (int index = 0; index < 60; index++) {
            mvc.perform(get("/api/v1/public/missing-" + index).header("Host", HOST).with(request -> {
                request.setRemoteAddr("203.0.113.21"); return request;
            })).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/public/missing").header("Host", HOST).with(request -> {
            request.setRemoteAddr("203.0.113.21"); return request;
        })).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/health").with(request -> { request.setRemoteAddr("203.0.113.21"); return request; }))
                .andExpect(status().isOk());
    }

    @Test void T_01_15_meUsesAccountAcrossIpsAndChildRoutesWithoutSharingOtherAccounts() throws Exception {
        for (int index = 0; index < 600; index++) {
            // A missing child route exercises the quota without performing 600 password grants.
            mvc.perform(get("/api/v1/me/missing").header("Host", HOST).with(jwt().jwt(token ->
                    token.subject("rate-account-a").claim("clubId", "club-a"))))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/me").header("Host", HOST).with(jwt().jwt(token ->
                        token.subject("rate-account-a").claim("clubId", "club-a")))
                        .with(request -> { request.setRemoteAddr("203.0.113.32"); return request; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        mvc.perform(get("/api/v1/me/missing").header("Host", HOST).with(jwt().jwt(token ->
                token.subject("rate-account-b").claim("clubId", "club-a"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me").header("Host", HOST)).andExpect(status().isUnauthorized());
        assertThat(mongo.findAll(SecurityEvent.class)).anySatisfy(event -> {
            assertThat(event.accountId()).isEqualTo("rate-account-a");
            assertThat(event.clubId()).isEqualTo("club-a");
            assertThat(event.type().name()).isEqualTo("RATE_LIMITED");
        });
    }
}
