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
}
