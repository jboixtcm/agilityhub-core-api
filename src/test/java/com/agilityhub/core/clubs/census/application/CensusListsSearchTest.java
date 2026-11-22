package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusListProjection;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** AUTHZ-01 (S03 R-03-22, R-03-31): the members list searches the ID number only for an ADMIN. No Spring context or Mongo. */
class CensusListsSearchTest {
    final CensusListProjection projection = mock(CensusListProjection.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final CensusLists lists = new CensusLists(projection, configs);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        TenantContext.clear();
        tenant = TenantContext.open("club-a");
        var config = mock(ClubConfig.class);
        when(config.modules()).thenReturn(Set.of(Module.values()));
        when(config.get("levels.enabled", Boolean.class)).thenReturn(true);
        when(configs.get("club-a")).thenReturn(config);
    }
    @AfterEach void tearDown() { tenant.close(); SecurityContextHolder.clearContext(); }

    ListDefinition definition(String key, String role) {
        var jwt = Jwt.withTokenValue("fictional").header("alg", "none").subject("account-a").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_" + role)));
        lists.dataset(key);
        var captor = ArgumentCaptor.forClass(ListDefinition.class);
        verify(projection, atLeastOnce()).dataset(captor.capture(), anyBoolean(), anyBoolean());
        return captor.getValue();
    }
    @Test void AUTHZ_01_anInstructorsMemberSearchDoesNotReachTheIdNumber() {
        assertThat(definition("members", "INSTRUCTOR").searchable())
                .containsExactly("fullName", "contactEmails.email", "phones.number", "dogs.name");
    }
    @Test void AUTHZ_01_anAdminsMemberSearchKeepsTheIdNumber() {
        assertThat(definition("members", "ADMIN").searchable())
                .containsExactly("fullName", "idDocument.number", "contactEmails.email", "phones.number", "dogs.name");
    }
    @Test void AUTHZ_01_theDogSearchIsTheSameForBothRoles() {
        assertThat(definition("dogs", "INSTRUCTOR").searchable()).containsExactly("name", "owner.fullName", "handlerName", "chip");
        clearInvocations(projection);
        assertThat(definition("dogs", "ADMIN").searchable()).containsExactly("name", "owner.fullName", "handlerName", "chip");
    }
}
