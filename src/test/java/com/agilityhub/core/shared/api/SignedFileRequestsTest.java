package com.agilityhub.core.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * INC-14 (E5-T24's review, detail #4): the signed file routes are recognised on the path Spring routes — decoded, without `;`
 * parameters, inside the context path — as the rate limiter reads it, never on the raw request URI.
 */
class SignedFileRequestsTest {
    static MockHttpServletRequest request(String method, String uri) {
        var request = new MockHttpServletRequest(method, uri); request.setRequestURI(uri); return request;
    }

    @Test void INC_14_theSignedRoutesAreReadOnTheRoutedPath() {
        assertThat(SignedFileRequests.matches(request("GET", "/api/v1/attachments/files/abc"))).isTrue();
        assertThat(SignedFileRequests.matches(request("PUT", "/api/v1/attachments/uploads/abc"))).isTrue();
        assertThat(SignedFileRequests.matches(request("GET", "/api/v1/remittances/files/club-a/abc"))).isTrue();
        // What Spring routes as the signup files route is that route here too.
        assertThat(SignedFileRequests.matches(request("GET", "/api/v1/signup/files;jsessionid=fictional"))).isTrue();
        assertThat(SignedFileRequests.matches(request("PUT", "/api/v1/signup/upl%6Fads"))).isTrue();
        var contextual = request("GET", "/core/api/v1/signup/files"); contextual.setContextPath("/core");
        assertThat(SignedFileRequests.matches(contextual)).isTrue();
        // Another method or route is not a signed route.
        assertThat(SignedFileRequests.matches(request("POST", "/api/v1/attachments/files/abc"))).isFalse();
        assertThat(SignedFileRequests.matches(request("GET", "/api/v1/attachments/files/abc/more"))).isFalse();
        assertThat(SignedFileRequests.matches(request("GET", "/api/v1/members"))).isFalse();
    }
}
