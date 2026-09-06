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
}
