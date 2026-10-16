package com.agilityhub.core.shared.api;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RequestTraceFilterTest {
    @Test void T_14_30_INC40_traceIsInMdcAndRemovedEvenAfterFailure() {
        MDC.clear();
        var request = new MockHttpServletRequest();
        assertThatThrownBy(() -> new RequestTraceFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(MDC.get("traceId")).isEqualTo(RequestTraceFilter.traceId(request));
            assertThat(MDC.get("clubId")).isEqualTo("-");
            assertThat(MDC.get("accountId")).isEqualTo("-");
            throw new jakarta.servlet.ServletException("fixture failure");
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    /**
     * E9-T06 (S16 R-16-11, E9-T04's question 3): the build session stream takes the access token as `?access_token=`; the trace
     * filter puts only `traceId`, `clubId` and `accountId` in the MDC every log line carries, never the URI or its query string.
     */
    @Test void T_16_10_theTraceNeverCarriesTheQueryString() throws Exception {
        MDC.clear();
        var request = new MockHttpServletRequest("GET", "/api/v1/build-sessions/session-a/events");
        request.setQueryString("access_token=eyJfictional.access.token");
        request.addParameter("access_token", "eyJfictional.access.token");
        var seen = new java.util.HashMap<String, String>();
        new RequestTraceFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.putAll(MDC.getCopyOfContextMap()));
        assertThat(seen).containsOnlyKeys("traceId", "clubId", "accountId");
        assertThat(seen.values()).noneMatch(value -> value.contains("access_token") || value.contains("eyJ") || value.contains("events"));
        assertThat(java.util.UUID.fromString(seen.get("traceId"))).isNotNull();
    }
}
