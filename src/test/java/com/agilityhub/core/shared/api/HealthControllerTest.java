package com.agilityhub.core.shared.api;

import com.agilityhub.core.configuration.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({SecurityConfiguration.class, ProjectInfoAutoConfiguration.class, com.agilityhub.core.configuration.I18nConfiguration.class,
        HealthControllerTest.SecurityTestConfiguration.class})
@ActiveProfiles("test")
class HealthControllerTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfiguration {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
            var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new RateLimitFilter(
                    org.mockito.Mockito.mock(com.agilityhub.core.shared.application.RateLimits.class),
                    org.mockito.Mockito.mock(com.agilityhub.core.shared.application.SecurityEvents.class), null, null));
            registration.setEnabled(false);
            return registration;
        }
        @org.springframework.context.annotation.Bean
        org.springframework.web.cors.CorsConfigurationSource clubCors() {
            return request -> new org.springframework.web.cors.CorsConfiguration();
        }
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.agilityhub.core.shared.application.AccountAccess accountAccess;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.agilityhub.core.identity.application.ImpersonationService impersonations;

    @Autowired
    MockMvc mvc;

    @Autowired
    BuildProperties build;

    @Test
    void E0_T01_healthIsPublicAndMatchesBuildInfo() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value(build.getVersion()))
                .andExpect(jsonPath("$.builtAt").value(build.getTime().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"})
    void E0_T01_healthIsAccessibleToEveryRole(String role) throws Exception {
        mvc.perform(get("/api/v1/health").with(user("health-check@example.test").roles(role)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"club-a.example.test", "club-b.example.test"})
    void E0_T01_healthIsGlobalAndIndependentOfTenant(String host) throws Exception {
        mvc.perform(get("/api/v1/health").header("X-Club-Host", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value(build.getVersion()))
                .andExpect(jsonPath("$.builtAt").value(build.getTime().toString()))
                .andExpect(jsonPath("$.clubId").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/v3/api-docs"})
    void E0_T01_otherPathsAreNotPublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void E0_T04_unknownApiRouteUsesErrorContract() throws Exception {
        mvc.perform(get("/api/v1/private").with(user("member@example.test").roles("MEMBER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No s'ha trobat el recurs."))
                .andExpect(jsonPath("$.details").isMap())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void E0_T01_postHealthIsNotPermitted() throws Exception {
        mvc.perform(post("/api/v1/health").with(csrf()))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void E0_T08_controllerAndSecurityErrorsAreLocalizedInSpanish() throws Exception {
        mvc.perform(get("/api/v1/missing").with(user("member@example.test").roles("MEMBER")).header("Accept-Language", "es"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("No se ha encontrado el recurso."));
        mvc.perform(get("/actuator/health").header("Accept-Language", "es"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/actuator/info").with(user("member@example.test").roles("MEMBER"))
                .header("Accept-Language", "es"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("No tienes permiso para realizar esta acción."));
    }
}
