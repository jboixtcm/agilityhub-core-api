package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubLocaleSettingsProviderTest {
    @Test void E0_T08_localeSettingsUseTrustedTenantScopeAndHandleMissingClubs() {
        var repository = mock(ClubRepository.class);
        var club = mock(Club.class);
        when(club.locales()).thenReturn(List.of("ca", "es"));
        when(club.defaultLocale()).thenReturn("ca");
        when(repository.findById("club-a")).thenAnswer(invocation -> {
            assertThat(TenantContext.require()).isEqualTo("club-a");
            return Optional.of(club);
        });
        when(repository.findById("missing")).thenReturn(Optional.empty());
        var provider = new ClubLocaleSettingsProvider(repository);
        assertThat(provider.settings("club-a")).hasValueSatisfying(settings -> {
            assertThat(settings.locales()).containsExactly("ca", "es");
            assertThat(settings.defaultLocale()).isEqualTo("ca");
        });
        assertThat(provider.settings("missing")).isEmpty();
        assertThat(TenantContext.current()).isNull();
        try (var tenant = TenantContext.open("club-b")) {
            assertThatThrownBy(() -> provider.settings("club-a"))
                    .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class)
                    .hasMessage("TENANT_MISMATCH");
        }
    }
}
