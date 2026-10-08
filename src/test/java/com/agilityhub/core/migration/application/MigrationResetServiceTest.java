package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.persistence.MigrationResetRepository;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** T-18-16 (R-18-14): `migration:playoff --reset` never runs with the production profile, nor without the slug typed twice. */
class MigrationResetServiceTest {
    final MigrationResetRepository data = mock(MigrationResetRepository.class);
    final ClubDefinitions definitions = mock(ClubDefinitions.class);
    final MigrationClubAccess clubs = mock(MigrationClubAccess.class);
    final Environment environment = mock(Environment.class);
    final MigrationResetService service = new MigrationResetService(data, definitions, clubs, mock(ClubConfigService.class),
            mock(MigrationResetChanges.class), environment);

    @Test void T_18_16_theProductionProfileRefusesTheResetBeforeTouchingAnything() {
        when(environment.matchesProfiles("prod", "production")).thenReturn(true);
        when(environment.matchesProfiles("local", "staging", "test")).thenReturn(true);
        assertThatThrownBy(() -> service.reset("canic", Path.of("seeds/club-canic.yaml"), "canic", "canic"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(data, definitions, clubs);
    }

    @Test void T_18_16_aDifferentSecondSlugRefusesTheResetBeforeTouchingAnything() {
        when(environment.matchesProfiles("local", "staging", "test")).thenReturn(true);
        assertThatThrownBy(() -> service.reset("canic", Path.of("seeds/club-canic.yaml"), "canic", "canix"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
        assertThatThrownBy(() -> service.reset("canic", Path.of("seeds/club-canic.yaml"), null, "canic"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
        verifyNoInteractions(data, definitions, clubs);
    }
}
