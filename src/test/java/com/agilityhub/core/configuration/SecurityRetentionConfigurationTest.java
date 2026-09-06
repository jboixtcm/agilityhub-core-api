package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.platform.persistence.SecurityEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SecurityRetentionConfigurationTest {
    @Test void E0_T12_retentionUsesCatalogDefaultAndAllowsAnExplicitSystemOverride() throws Exception {
        var catalog = new ParameterCatalog(new ObjectMapper());
        var repository = mock(SecurityEventRepository.class);
        var configuration = new SecurityBaselineConfiguration();
        configuration.securityEventIndexes(repository, catalog, new MockEnvironment())
                .run(new DefaultApplicationArguments());
        verify(repository).ensureIndexes(90);
        configuration.securityEventIndexes(repository, catalog,
                        new MockEnvironment().withProperty("security.eventRetentionDays", "180"))
                .run(new DefaultApplicationArguments());
        verify(repository).ensureIndexes(180);
        assertThat(catalog.get("security.eventRetentionDays").editableBy()).isEqualTo("PLATFORM");
    }
}
