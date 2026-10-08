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
        "GET,/api/v1/signup/files", "PUT,/api/v1/signup/uploads", "GET,/api/v1/bookings/example/calendar.ics"
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
        verify(events).record(SecurityEvents.Type.RATE_LIMITED, null, null);
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
        verify(events, times(2)).record(SecurityEvents.Type.RATE_LIMITED, null, null);
        clock.advance(Duration.ofMinutes(1));
        assertThat(call(filter, method, path).getStatus()).isEqualTo(204);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        return response;
    }
}
