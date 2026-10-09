package com.agilityhub.core.identity.application;

import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link AuthSettings#enabled} (S01 T-01-09, `auth.checkCompromisedPasswords`, type bool, editable by the
 * platform, catalog.yaml:1109-1111): a club that switched the check off reads false. The club's configuration is a real
 * {@link ClubConfig} holding the stored boolean (ClubConfig.java:22-32 returns it as is); the service and catalog are mocks.
 */
class AuthSettingsSurvivorsTest {
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final AuthSettings settings = new AuthSettings(clubs, mock(ParameterCatalog.class));

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void T_01_09_aClubThatSwitchedOffTheCompromisedPasswordCheckReadsItDisabled() {
        when(clubs.get("club-a")).thenReturn(new ClubConfig(null, Map.of("auth.checkCompromisedPasswords", false), Set.of(), null, Map.of()));

        try (var scope = TenantContext.open("club-a")) {
            assertThat(settings.enabled("auth.checkCompromisedPasswords")).isFalse();
        }
    }
}
