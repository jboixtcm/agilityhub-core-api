package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
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
 * E11-T06 PIT survivors of {@link InvoiceLists} (S12 §6 `GET /invoices`, D6's list; no S12 test id covers the provider itself,
 * hence the task's prefix): the provider serves the `invoices` key and labels a filter value as stored.
 */
class InvoiceListsSurvivorsTest {
    static final String CLUB = "club-a";
    final ClubConfigService configs = mock(ClubConfigService.class);
    final InvoiceLists lists = new InvoiceLists(configs, mock(InvoiceRepository.class));

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void E11_T06_theProviderServesTheInvoicesKey() {
        assertThat(lists.keys()).containsExactly("invoices");
    }

    @Test void E11_T06_aFilterValueIsLabelledAsStored() {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("account-admin").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.BILLING), null, Map.of()));
        try (var tenant = TenantContext.open(CLUB)) {
            var dataset = lists.dataset("invoices");
            assertThat(dataset.label().apply("status", "PAID")).isEqualTo("PAID");
            assertThat(dataset.label().apply("member.memberNumber", 7)).isEqualTo("7");
        }
    }
}
