package com.agilityhub.core.identity.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link PasswordGrantConverter} (S01 R-01-11): HTTP Basic client authentication accepts any
 * non-empty client id (RFC 6749 §2.3.1 puts no minimum length on it), so a one-character id before the colon is parsed and
 * left to client authentication, and only an empty one is a malformed request.
 */
class PasswordGrantConverterSurvivorsTest {
    final PasswordGrantConverter converter = new PasswordGrantConverter(mock(RefreshCookies.class));

    static MockHttpServletRequest passwordGrant(String basic) {
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.addParameter("grant_type", "password");
        request.addParameter("username", "laura@example.test");
        request.addParameter("password", "fictional-passphrase");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    @Test void T_01_13_aOneCharacterBasicClientIdIsParsed() {
        var token = (PasswordGrantAuthenticationToken) converter.convert(passwordGrant("x:client-secret"));

        assertThat(token.clientId()).isEqualTo("x");
        assertThat(token.clientSecret()).isEqualTo("client-secret");
    }

    @Test void T_01_13_anEmptyBasicClientIdIsAMalformedRequest() {
        assertThatThrownBy(() -> converter.convert(passwordGrant(":client-secret"))).isInstanceOf(OAuth2AuthenticationException.class);
    }
}
