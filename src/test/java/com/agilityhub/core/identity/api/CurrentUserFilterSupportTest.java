package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.ImpersonationService;
import com.agilityhub.core.identity.application.SupportAccessService;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * S17 R-17-10 (E10-T04 round 2, review #1): the filter's support restriction reads the path Spring routes (decoded, without `;`
 * parameters), so an encoded letter does not take a support token past it. The handlers refuse it too (`@SupportRestricted`,
 * `SupportAccessIT`); this test checks the filter on its own.
 */
class CurrentUserFilterSupportTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void T_17_09_R_17_10_theSupportRestrictionReadsThePathSpringRoutes() throws Exception {
        var supports = mock(SupportAccessService.class);
        when(supports.validate(any(), anyBoolean())).thenReturn(new CurrentUser.Support("grant", "actor", "Pat Support", Instant.parse("2026-08-25T09:00:00Z")));
        var filter = new CurrentUserFilter(mock(ImpersonationService.class), supports);
        var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("actor").claim("support", true).claim("clubId", "club-a").build();
        for (var restricted : List.of(new String[] {"GET", "/api/v1/members/%65xport"}, new String[] {"POST", "/api/v1/members/%65xport"},
                new String[] {"GET", "/api/v1/%65xports/some-export/download"}, new String[] {"GET", "/api/v1/remittances/some-remittance/%66ile"},
                new String[] {"POST", "/api/v1/members/puig/%64ata-export"}, new String[] {"GET", "/api/v1/billing/%65xports"},
                new String[] {"GET", "/api/v1/members/export;format=xlsx"})) {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            var chain = new MockFilterChain();
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(restricted[0], restricted[1]), new MockHttpServletResponse(), chain))
                    .as(restricted[0] + " " + restricted[1]).isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.code()).isEqualTo(ErrorCode.SUPPORT_RESTRICTED));
            assertThat(chain.getRequest()).isNull();
        }
        // Any other route of the club's administration goes on, with the support access in the request's user.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/members/puig"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
