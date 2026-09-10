package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.LocaleSettingsProvider.Settings;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestLocaleResolverTest {
    private final IcuMessageSource messages = new IcuMessageSource();
    private final Map<String, Settings> clubs = Map.of(
            "club-a", new Settings(List.of("es", "en"), "es"),
            "club-b", new Settings(List.of("ca"), "ca"),
            "unsupported", new Settings(List.of("fr"), "fr"),
            "no-default", new Settings(List.of(), null));
    private final RequestLocaleResolver resolver = new RequestLocaleResolver(messages,
            id -> Optional.ofNullable(clubs.get(id)));

    RequestLocaleResolverTest() throws java.io.IOException { }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); TenantContext.clear(); }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        en;q=0.4,es;q=0.9 | es
        es-MX,en;q=0.8 | es
        ca,en;q=0.5 | en
        fr | es
        en;q=0,es;q=0.5 | es
        *;q=0.5,en;q=0 | es
        garbage;q=wrong | es
        en;q=0,es;q=0 | es
        """)
    void E0_T08_acceptLanguageUsesWeightsRegionsAndOnlyClubLanguages(String header, String expected) {
        try (var tenant = TenantContext.open("club-a")) {
            assertThat(resolve(header).toLanguageTag()).isEqualTo(expected);
        }
    }
    @Test void E0_T08_tenantsCannotInheritAnotherClubsLocale() {
        try (var tenant = TenantContext.open("club-a")) {
            assertThat(resolve("en")).isEqualTo(Locale.ENGLISH);
            assertThat(resolve(null)).isEqualTo(Locale.forLanguageTag("es"));
        }
        try (var tenant = TenantContext.open("club-b")) {
            assertThat(resolve("en")).isEqualTo(Locale.forLanguageTag("ca"));
        }
        for (String club : List.of("missing", "unsupported", "no-default")) {
            try (var tenant = TenantContext.open(club)) {
                assertThat(resolve(null)).isEqualTo(Locale.forLanguageTag("ca"));
            }
        }
    }
    @Test void E0_T08_authenticatedAccountLocaleWinsAndUnsupportedAccountUsesClubDefault() {
        authenticate("en-GB", "club-b");
        assertThat(resolve("ca")).isEqualTo(Locale.UK);
        authenticate("fr", "club-a");
        assertThat(resolve("en")).isEqualTo(Locale.forLanguageTag("es"));
        authenticate("", "club-a");
        assertThat(resolve("en")).isEqualTo(Locale.ENGLISH);
        authenticate(null, "club-a");
        assertThat(resolve("en")).isEqualTo(Locale.ENGLISH);
        authenticate("es", "");
        assertThat(resolve("en")).isEqualTo(Locale.forLanguageTag("es"));
        authenticate("es", null);
        assertThat(resolve("en")).isEqualTo(Locale.forLanguageTag("es"));
        var token = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        token.setAuthenticated(false);
        assertThat(resolve("en")).isEqualTo(Locale.ENGLISH);
        SecurityContextHolder.clearContext();
        assertThat(resolve(null)).isEqualTo(Locale.forLanguageTag("ca"));
        assertThat(resolve("fr")).isEqualTo(Locale.forLanguageTag("ca"));
        assertThat(resolve("es")).isEqualTo(Locale.forLanguageTag("es"));
        assertThat(resolve("*")).isIn(messages.supportedLocales());
    }
    @Test void E0_T08_mvcServicesAndErrorsUseTheSameLocaleAndCleanUp() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).setLocaleResolver(resolver)
                .setControllerAdvice(new ApiExceptionHandler(messages, resolver))
                .addFilters(new RequestLocaleFilter(resolver)).build();
        try (var tenant = TenantContext.open("club-a")) {
            mvc.perform(get("/locale").header("Accept-Language", "ca,en;q=0.5"))
                    .andExpect(status().isOk()).andExpect(content().string("en"))
                    .andExpect(header().string("Content-Language", "en"));
            mvc.perform(get("/error").header("Accept-Language", "es"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("No se ha encontrado el recurso."));
        }
        assertThat(org.springframework.context.i18n.LocaleContextHolder.getLocaleContext()).isNull();
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "es");
        try (var previous = LocaleContext.open(Locale.ENGLISH)) {
            assertThatThrownBy(() -> new RequestLocaleFilter(resolver).doFilter(request,
                    new MockHttpServletResponse(), (req, res) -> { throw new jakarta.servlet.ServletException("failure"); }))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
            assertThat(LocaleContext.current()).isEqualTo(Locale.ENGLISH);
        }
        assertThatThrownBy(() -> resolver.setLocale(request, new MockHttpServletResponse(), Locale.ENGLISH))
                .isInstanceOf(UnsupportedOperationException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/api/v1/health", "/actuator/health"})
    void E3_T06_INC01_healthSkipsLocaleLookupEvenWithAmbientTenantAndJwt(String path) throws Exception {
        var settings = org.mockito.Mockito.mock(com.agilityhub.core.shared.application.LocaleSettingsProvider.class);
        var healthResolver = new RequestLocaleResolver(messages, settings);
        authenticate("es", "missing-club");
        var request = new MockHttpServletRequest("GET", "/core" + path);
        request.setContextPath("/core");
        request.addHeader("Accept-Language", "es");
        try (var tenant = TenantContext.open("missing-club")) {
            assertThat(healthResolver.resolveLocale(request)).isEqualTo(LocaleContext.DEFAULT);
            var response = new MockHttpServletResponse();
            new RequestLocaleFilter(healthResolver).doFilter(request, response, (req, res) -> res.getWriter().write("UP"));
            assertThat(response.getContentAsString()).isEqualTo("UP");
            assertThat(response.getHeader("Content-Language")).isNull();
        }
        org.mockito.Mockito.verifyNoInteractions(settings);
    }
    private Locale resolve(String header) {
        var request = new MockHttpServletRequest();
        if (header != null) { request.addHeader("Accept-Language", header); }
        return resolver.resolveLocale(request);
    }
    private void authenticate(String locale, String clubId) {
        var builder = Jwt.withTokenValue("test-token").header("alg", "none").subject("account-a");
        if (locale != null) { builder.claim("locale", locale); }
        if (clubId != null) { builder.claim("clubId", clubId); }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build(),
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
    }
    @RestController static class Probe {
        @GetMapping("/locale") String locale() { return LocaleContext.current().toLanguageTag(); }
        @GetMapping("/error") void error() { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
}
