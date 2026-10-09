package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link RemittanceLists} (S12 §6 D6 «Remeses», R-12-28; T-12-32): the provider serves the
 * `remittances` key and prints a cell's raw value.
 */
class RemittanceListsSurvivorsTest {
    static final String CLUB = "club-a";
    final ClubConfigService configs = mock(ClubConfigService.class);
    final RemittanceLists lists = new RemittanceLists(configs);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void T_12_32_theProviderServesTheRemittancesKey() {
        assertThat(lists.keys()).containsExactly("remittances");
    }

    @Test void T_12_32_cellsArePrintedAsStored() {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("account-admin").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.BILLING), null, Map.of()));
        try (var tenant = TenantContext.open(CLUB)) {
            var dataset = lists.dataset("remittances");
            assertThat(dataset.label().apply("count", 12)).isEqualTo("12");
            assertThat(dataset.label().apply("submittedAt", Instant.parse("2026-10-02T00:00:00Z"))).isEqualTo("2026-10-02T00:00:00Z");
        }
    }
}
