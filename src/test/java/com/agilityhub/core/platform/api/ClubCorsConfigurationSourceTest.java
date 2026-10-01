package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClubCorsConfigurationSourceTest {
    @Test void T_01_25_localAllowsRegisteredPendingHostsAndPortsWithCachedLookupAndInvalidation() {
        var repository = mock(ClubRepository.class);
        var hosts = new HostTenantResolver(repository);
        var cors = new ClubCorsConfigurationSource(hosts, List.of("id.example.test"), true);
        when(repository.findByAnyHost("pending.example.test"))
                .thenReturn(Optional.of(PlatformFixtures.club("club-a", "app.example.test")));
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "http://pending.example.test:5173");
        assertThat(cors.getCorsConfiguration(request).getAllowedOrigins()).containsExactly("http://pending.example.test:5173");
        cors.getCorsConfiguration(request);
        verify(repository, times(1)).findByAnyHost("pending.example.test");
        when(repository.findByAnyHost("pending.example.test")).thenReturn(Optional.empty());
        hosts.invalidate();
        assertThat(cors.getCorsConfiguration(request).getAllowedOrigins()).isNull();
        verify(repository, times(2)).findByAnyHost("pending.example.test");
        request.removeHeader("Origin"); request.addHeader("Origin", "ftp://id.example.test");
        assertThat(cors.getCorsConfiguration(request).getAllowedOrigins()).isNull();
    }

    @Test void T_01_25_E11_localPendingDomainMustBelongToTheTargetClub() {
        var repository = mock(ClubRepository.class);
        when(repository.findByHost("api.example.test")).thenReturn(Optional.of(PlatformFixtures.club("club-a", "api.example.test")));
        when(repository.findByAnyHost("pending.example.test")).thenReturn(Optional.of(PlatformFixtures.club("club-a", "api.example.test")));
        when(repository.findByAnyHost("other.example.test")).thenReturn(Optional.of(PlatformFixtures.club("club-b", "other.example.test")));
        var cors = new ClubCorsConfigurationSource(new HostTenantResolver(repository), List.of(), true);
        var request = new MockHttpServletRequest("GET", "/api/v1/branding");
        request.addHeader("Host", "api.example.test"); request.addHeader("Origin", "http://pending.example.test:5173");
        assertThat(cors.getCorsConfiguration(request).getAllowedOrigins()).containsExactly("http://pending.example.test:5173");
        request.removeHeader("Origin"); request.addHeader("Origin", "http://other.example.test:5173");
        assertThat(cors.getCorsConfiguration(request).getAllowedOrigins()).isNull();
    }

    /** E5-T27 round 2 (review #2, ruling E71): the health's CORS reads the platform hosts only; a club origin is never looked up. */
    @Test void E71_theHealthAllowsThePlatformHostsWithoutEverReadingTheClubs() {
        for (boolean local : new boolean[]{false, true}) {
            var repository = mock(ClubRepository.class);
            var cors = new ClubCorsConfigurationSource(new HostTenantResolver(repository), List.of("id.example.test"), local);
            var request = new MockHttpServletRequest("GET", "/api/v1/health");
            request.addHeader("Origin", "https://id.example.test");
            var platform = cors.getCorsConfiguration(request);
            assertThat(platform.getAllowedOrigins()).containsExactly("https://id.example.test");
            assertThat(platform.getAllowCredentials()).isFalse();
            for (String origin : new String[]{"https://app.example.test", "http://pending.example.test:5173", "https://id.example.test/path", "null"}) {
                request.removeHeader("Origin"); request.addHeader("Origin", origin);
                assertThat(cors.getCorsConfiguration(request)).as(origin).isNull();
            }
            request.removeHeader("Origin");
            assertThat(cors.getCorsConfiguration(request)).isNull();
            verifyNoInteractions(repository);
            var branding = new MockHttpServletRequest("GET", "/api/v1/branding");
            branding.addHeader("Origin", "https://app.example.test");
            assertThat(cors.getCorsConfiguration(branding).getAllowedOrigins()).isNull();
            if (local) { verify(repository).findByAnyHost("app.example.test"); } else { verify(repository).findByHost("app.example.test"); }
        }
    }
}
