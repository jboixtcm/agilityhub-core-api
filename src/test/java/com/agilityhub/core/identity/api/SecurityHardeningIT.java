package com.agilityhub.core.identity.api;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
@Import(SecurityHardeningIT.PrivacyProbe.class)
@TestPropertySource(properties = {"core.security.rate-limits.enabled=true", "core.security.rate-limits.anonymous.capacity=2",
        "core.security.rate-limits.webhook.capacity=2", "core.security.rate-limits.signed-file.capacity=2",
        "core.security.rate-limits.handoff.capacity=2", "core.security.rate-limits.token-account.capacity=2"})
class SecurityHardeningIT extends IdentityIntegrationSupport {
    @RestController static class PrivacyProbe {
        @PostMapping("/api/v1/e11-privacy-probe") void fail(@RequestBody String body) {
            assertThat(org.slf4j.MDC.get("accountId")).isEqualTo("account-a");
            assertThat(org.slf4j.MDC.get("clubId")).isEqualTo("club-a");
            throw new IllegalStateException(body);
        }
    }

    @Test void T_14_30_aRealRequestLogsIdsButNeverItsPersonalDataOrBody(CapturedOutput output) throws Exception {
        String body = "privacy@example.test +34 600 123 456 ES0000000000000000000000 request-body-marker";
        var response = mvc.perform(post("/api/v1/e11-privacy-probe").header("Host", HOST)
                        .header("X-Fixture", body).contentType("application/json").content(body)
                        .with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse();
        String trace = mapper.readTree(response.getContentAsString()).path("traceId").asText();
        assertThat(output.getAll()).contains("traceId=" + trace, "clubId=club-a", "accountId=account-a")
                .doesNotContain("privacy@example.test", "+34 600 123 456", "ES0000000000000000000000", "request-body-marker");
        assertThat(org.slf4j.MDC.get("accountId")).isNull();
    }

    @Test void T_01_15_E11_newFilterPoliciesUseTheCatalogOnRealResponsesAndRecover() throws Exception {
        String[] paths = {"/api/v1/email-unsubscribes", "/webhooks/email/sendgrid", "/api/v1/signup/uploads", "/api/v1/auth/handoff"};
        for (int index = 0; index < paths.length; index++) {
            final String address = "203.0.113." + (100 + index);
            String path = paths[index];
            for (int call = 0; call < 4; call++) {
                if (call == 3) { clock.advance(Duration.ofMinutes(1)); }
                var request = (path.endsWith("uploads") ? put(path) : post(path)).header("Host", HOST)
                        .contentType("application/json").with(r -> { r.setRemoteAddr(address); return r; });
                var result = mvc.perform(request);
                if (call == 2) {
                    result.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                            .andExpect(jsonPath("$.details").isMap()).andExpect(jsonPath("$.traceId").isNotEmpty())
                            .andExpect(header().string("Retry-After", "60"));
                } else { assertThat(result.andReturn().getResponse().getStatus()).isNotEqualTo(429); }
            }
            assertThat(mongo.getCollection("security_events").countDocuments(new org.bson.Document("type", "RATE_LIMITED").append("ip", address)))
                    .isEqualTo(1);
        }
    }

    @Test void T_01_15_E11_tokenAccountQuotaHoldsAcrossIpsAndExpires() throws Exception {
        for (int call = 0; call < 4; call++) {
            if (call == 3) { clock.advance(Duration.ofMinutes(1)); }
            final String address = "203.0.113." + (150 + call);
            var result = mvc.perform(post("/oauth2/token").header("Host", HOST).contentType("application/x-www-form-urlencoded")
                    .param("grant_type", "password").param("username", call == 2 ? " QUOTA@EXAMPLE.TEST " : "quota@example.test").param("password", "incorrect")
                    .with(r -> { r.setRemoteAddr(address); return r; }));
            if (call == 2) { result.andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60")); }
            else { result.andExpect(status().isUnauthorized()); }
        }
    }

    @Test void T_01_15_E11_handoffAccountQuotaCannotBeAvoidedByChangingIp() throws Exception {
        for (int call = 0; call < 4; call++) {
            if (call == 3) { clock.advance(Duration.ofMinutes(1)); }
            final String address = "203.0.113." + (170 + call);
            var response = mvc.perform(post("/api/v1/auth/handoff").header("Host", HOST).contentType("application/json")
                    .content("{}")
                    .with(jwt().jwt(j -> j.subject("handoff-rate-account").claim("clubId", "club-a")))
                    .with(r -> { r.setRemoteAddr(address); return r; }));
            if (call == 2) { response.andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60")); }
            else { response.andExpect(status().isBadRequest()); }
        }
    }

    @Test void T_01_25_E11_jsonErrorsOpenApiAndTokenResponsesHaveTheBaseline() throws Exception {
        for (String path : new String[]{"/api/v1/health", "/api/v1/branding", "/api/v1/openapi.json", "/api/v1/me", "/oauth2/token"}) {
            var request = (path.equals("/oauth2/token") ? post(path) : get(path)).header("Host", HOST);
            mvc.perform(request).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Content-Security-Policy", com.agilityhub.core.shared.application.ContentSecurityPolicies.API))
                    .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
        mvc.perform(get("/api/v1/me").header("Host", HOST)
                        .with(jwt().jwt(j -> j.subject("account-a").claim("clubId", "club-a"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }
}
