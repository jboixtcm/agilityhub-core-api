package com.agilityhub.core.shared.api;

import com.agilityhub.core.configuration.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
                    org.mockito.Mockito.mock(com.agilityhub.core.shared.application.SecurityEvents.class), null, null, com.agilityhub.core.identity.domain.Email::normalize));
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

    /** E10-T04: the security chain's CurrentUserFilter validates support access tokens too. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.agilityhub.core.identity.application.SupportAccessService supports;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.agilityhub.core.shared.persistence.DatabaseProbe database;

    @org.junit.jupiter.api.BeforeEach
    void databaseUp() { org.mockito.Mockito.when(database.up()).thenReturn(true); }

    @Autowired
    MockMvc mvc;

    @Autowired
    BuildProperties build;

    @Test
    void E0_T01_healthIsPublicAndMatchesBuildInfo() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value(build.getVersion()))
                .andExpect(jsonPath("$.builtAt").value(build.getTime().toString()))
                .andExpect(jsonPath("$.commit").value(recordedCommit()));
    }

    /**
     * REL-02 (owner decision of 13 Nov 2026): the build is a real release version, `x.y.z` from 1.0.0 on, never a `-SNAPSHOT` or
     * a 0.x, and the health answers the version the pom names (build-info records it).
     */
    @Test
    void REL_02_healthAnswersTheReleaseVersionThePomNames() throws Exception {
        var pom = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.File("pom.xml"));
        String version = javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate("/project/version", pom);
        org.assertj.core.api.Assertions.assertThat(version).as("pom.xml project version").matches("[1-9][0-9]*\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
        org.assertj.core.api.Assertions.assertThat(build.getVersion()).as("build-info version").isEqualTo(version);
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(version));
    }

    /** REL-02: the commit build-info recorded (pom.xml `git.commit`), or null for a build given none (`unknown`). */
    private org.hamcrest.Matcher<Object> recordedCommit() {
        String commit = build.get("commit");
        return commit == null || commit.equals("unknown") ? org.hamcrest.Matchers.nullValue() : org.hamcrest.Matchers.equalTo(commit);
    }

    /**
     * E5-T27 step 5 (A7-07, INC-01 semantics): without a successful database ping the health answers 503 with the same
     * envelope and `status: DOWN`; the route stays public and needs no tenant.
     */
    @Test
    void E5_T27_healthIsDownWith503AndTheSameEnvelopeWhenTheDatabasePingFails() throws Exception {
        org.mockito.Mockito.when(database.up()).thenReturn(false);
        mvc.perform(get("/api/v1/health").header("X-Club-Host", "club-a.example.test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.version").value(build.getVersion()))
                .andExpect(jsonPath("$.builtAt").value(build.getTime().toString()))
                .andExpect(jsonPath("$.commit").value(recordedCommit()));
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
