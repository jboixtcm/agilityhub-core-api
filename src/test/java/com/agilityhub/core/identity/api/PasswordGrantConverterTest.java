package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordGrantConverterTest {
    @Test void T_01_07_conflictingAuthAndDuplicateClientAreRejectedAndCredentialsAreErasable() {
        var request = new MockHttpServletRequest(); request.addParameter("grant_type", "password");
        request.addHeader("Authorization", "Basic invalid");
        assertThatThrownBy(() -> new PasswordGrantConverter(mock(RefreshCookies.class)).convert(request)).isInstanceOf(OAuth2AuthenticationException.class);
        request.removeHeader("Authorization"); request.addParameter("client_id", "a", "b");
        assertThatThrownBy(() -> new PasswordGrantConverter(mock(RefreshCookies.class)).convert(request)).isInstanceOf(OAuth2AuthenticationException.class);
        var provider = new PasswordGrantProvider(mock(TokenService.class), mock(RegisteredClientRepository.class), mock(com.agilityhub.core.identity.application.HandoffService.class), mock(com.agilityhub.core.identity.application.OidcService.class));
        assertThat(provider.supports(PasswordGrantAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(String.class)).isFalse();
        var token = new PasswordGrantAuthenticationToken("password", "unknown", "Example", "secret");
        assertThatThrownBy(() -> provider.authenticate(token)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(token.getCredentials()).isNull();
        assertThat(token.toString()).doesNotContain("secret");
    }
    @Test void T_01_01_membershipRejectsEmptyRolesAndUnavailableDefault() {
        assertThatThrownBy(() -> new com.agilityhub.core.identity.persistence.Membership("id", "account", "club", null,
                java.util.Set.of(), com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.agilityhub.core.identity.persistence.Membership("id", "account", "club", null,
                java.util.Set.of(com.agilityhub.core.identity.domain.Role.MEMBER), com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,
                com.agilityhub.core.identity.domain.Role.ADMIN)).isInstanceOf(IllegalArgumentException.class);
    }
}
