package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.support.MockClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnonymousRateLimitsTest {
    @ParameterizedTest
    @CsvSource({
        "GET,/.well-known/jwks.json", "GET,/.well-known/openid-configuration", "GET,/oauth2/jwks",
        "GET,/oauth2/authorize", "GET,/connect/logout", "POST,/api/v1/email-unsubscribes",
        "POST,/api/v1/auth/handoff", "GET,/api/v1/manifest.webmanifest", "GET,/api/v1/signup",
        "GET,/api/v1/country-profile", "GET,/api/v1/country-profile/postal-codes/00000",
        "GET,/api/v1/checkout-sessions/example", "POST,/webhooks/email/sendgrid", "POST,/webhooks/stripe/example",
        "PUT,/api/v1/attachments/uploads/example", "GET,/api/v1/attachments/files/example",
        "GET,/api/v1/signup/files", "PUT,/api/v1/signup/uploads", "GET,/api/v1/bookings/example/calendar.ics",
        // E11-T06: E8's signed remittance-file download (SignedFileRequests) is anonymous too.
        "GET,/api/v1/remittances/files/example-club/example"
    })
    void T_01_15_E11_anonymousFamiliesRefuseWithRetryAndEventThenRecover(String method, String path) throws Exception {
        var clock = new MockClock(Instant.parse("2026-01-01T00:00:00Z"));
        var policies = new EnumMap<RateLimits.Route, RateLimits.Limit>(RateLimits.Route.class);
        for (var route : RateLimits.Route.values()) { policies.put(route, new RateLimits.Limit(2, Duration.ofMinutes(1))); }
        var events = mock(SecurityEvents.class);
        var errors = mock(ApiExceptionHandler.class);
        when(errors.body(any(), any())).thenReturn(new ApiError("RATE_LIMITED", "Rate limited", Map.of(), "fixture-trace"));
        var filter = new RateLimitFilter(new RateLimits(true, policies, clock), events, errors, new ObjectMapper(), com.agilityhub.core.identity.domain.Email::normalize);
        for (int i = 0; i < 2; i++) { assertThat(call(filter, method, path).getStatus()).isEqualTo(204); }
        var refused = call(filter, method, path);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
        var body = new ObjectMapper().readTree(refused.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.path("details").isObject()).isTrue();
        // S14 R-14-17 (E11-T01): the event names the limited route; no address on these anonymous routes.
        verify(events).record(eq(SecurityEvents.Type.RATE_LIMITED), isNull(), isNull(), isNull(), argThat(details -> details.containsKey("limit")));
        clock.advance(Duration.ofMinutes(1));
        assertThat(call(filter, method, path).getStatus()).isEqualTo(204);
    }
    @ParameterizedTest
    @CsvSource({"GET,/api/v1/country-profile,GET,/api/v1/checkout-sessions/example",
                "POST,/webhooks/email/sendgrid,POST,/webhooks/stripe/example"})
    void T_12_15_E11_paymentRoutesHaveIndependentQuotas(String otherMethod, String otherPath,
                                                       String method, String path) throws Exception {
        var clock = new MockClock(Instant.parse("2026-01-01T00:00:00Z"));
        var policies = new EnumMap<RateLimits.Route, RateLimits.Limit>(RateLimits.Route.class);
        for (var route : RateLimits.Route.values()) policies.put(route, new RateLimits.Limit(2, Duration.ofMinutes(1)));
        var events = mock(SecurityEvents.class);
        var messages = new com.agilityhub.core.shared.application.IcuMessageSource();
        var errors = new ApiExceptionHandler(messages, new RequestLocaleResolver(messages, ignored -> java.util.Optional.empty()));
        var filter = new RateLimitFilter(new RateLimits(true, policies, clock), events, errors, new ObjectMapper(),
                com.agilityhub.core.identity.domain.Email::normalize);
        for (int i = 0; i < 2; i++) assertThat(call(filter, otherMethod, otherPath).getStatus()).isEqualTo(204);
        assertThat(call(filter, otherMethod, otherPath).getStatus()).isEqualTo(429);
        for (int i = 0; i < 2; i++) assertThat(call(filter, method, path).getStatus()).isEqualTo(204);
        var refused = call(filter, method, path);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
        var body = new ObjectMapper().readTree(refused.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.path("details").isEmpty()).isTrue();
        assertThat(body.path("traceId").asText()).isNotBlank();
        verify(events, times(2)).record(eq(SecurityEvents.Type.RATE_LIMITED), isNull(), isNull(), isNull(), argThat(details -> details.containsKey("limit")));
        clock.advance(Duration.ofMinutes(1));
        assertThat(call(filter, method, path).getStatus()).isEqualTo(204);
    }

    /** INC-67 (E11-T08): Stripe sends every club's webhooks from the same addresses; one club's burst never delays another's. */
    @org.junit.jupiter.api.Test void INC_67_theStripeWebhookQuotaIsCountedPerClubAndAddress() throws Exception {
        var filter = filter(new MockClock(Instant.parse("2026-01-01T00:00:00Z")));
        for (int i = 0; i < 2; i++) { assertThat(call(filter, "POST", "/webhooks/stripe/club-a").getStatus()).isEqualTo(204); }
        assertThat(call(filter, "POST", "/webhooks/stripe/club-a").getStatus()).isEqualTo(429);
        assertThat(call(filter, "POST", "/webhooks/stripe/club-b").getStatus()).isEqualTo(204);
        assertThat(call(filter, "POST", "/webhooks/stripe/club-b", "192.0.2.10").getStatus()).isEqualTo(204);
        assertThat(call(filter, "POST", "/webhooks/stripe/club-b").getStatus()).isEqualTo(204);
        assertThat(call(filter, "POST", "/webhooks/stripe/club-b").getStatus()).isEqualTo(429);
    }

    /** INC-67 (E11-T08), CONVENCIONS_API §9: with a token, the checkout status is counted per account, not per shared address. */
    @org.junit.jupiter.api.Test void INC_67_anAuthenticatedCheckoutStatusIsCountedPerAccount() throws Exception {
        var filter = filter(new MockClock(Instant.parse("2026-01-01T00:00:00Z")));
        try {
            authenticate("account-a");
            for (int i = 0; i < 2; i++) { assertThat(call(filter, "GET", "/api/v1/checkout-sessions/example").getStatus()).isEqualTo(204); }
            assertThat(call(filter, "GET", "/api/v1/checkout-sessions/example").getStatus()).isEqualTo(429);
            assertThat(call(filter, "GET", "/api/v1/checkout-sessions/example", "192.0.2.10").getStatus()).isEqualTo(429);
            authenticate("account-b");
            assertThat(call(filter, "GET", "/api/v1/checkout-sessions/example").getStatus()).isEqualTo(204);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        // The anonymous applicant behind the same address keeps the address's own quota.
        for (int i = 0; i < 2; i++) { assertThat(call(filter, "GET", "/api/v1/checkout-sessions/example").getStatus()).isEqualTo(204); }
        assertThat(call(filter, "GET", "/api/v1/checkout-sessions/example").getStatus()).isEqualTo(429);
    }

    private static RateLimitFilter filter(MockClock clock) {
        var policies = new EnumMap<RateLimits.Route, RateLimits.Limit>(RateLimits.Route.class);
        for (var route : RateLimits.Route.values()) { policies.put(route, new RateLimits.Limit(2, Duration.ofMinutes(1))); }
        var errors = mock(ApiExceptionHandler.class);
        when(errors.body(any(), any())).thenReturn(new ApiError("RATE_LIMITED", "Rate limited", Map.of(), "fixture-trace"));
        return new RateLimitFilter(new RateLimits(true, policies, clock), mock(SecurityEvents.class), errors, new ObjectMapper(),
                com.agilityhub.core.identity.domain.Email::normalize);
    }

    private static void authenticate(String account) {
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-token").header("alg", "none")
                .subject(account).claim("clubId", "club-a").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, java.util.List.of()));
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path) throws Exception {
        return call(filter, method, path, "127.0.0.1");
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path, String address) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        return response;
    }
}
