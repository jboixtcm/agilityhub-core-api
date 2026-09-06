package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ResponseLocaleIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired com.agilityhub.core.platform.application.HostTenantResolver hosts;

    @BeforeEach void clubs() throws Exception {
        for (String language : List.of("ca", "es")) {
            String id = "locale-" + language;
            try (var input = getClass().getResourceAsStream("/fixtures/platform/club.json")) {
                var document = Document.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .append("_id", id).append("slug", id)
                        .append("locales", List.of(language)).append("defaultLocale", language);
                document.getList("domains", Document.class).forEach(domain ->
                        domain.put("host", id + "." + domain.getString("host")));
                mongo.getCollection("clubs").deleteOne(new Document("_id", id));
                mongo.getCollection("clubs").insertOne(document);
            }
        }
        hosts.invalidate();
    }
    @Test void E0_T08_tenantDefaultsAndAccountPreferencesReachRealControllerErrors() throws Exception {
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) {
            mvc.perform(get("/api/v1/missing").with(jwt().jwt(token -> token.subject("account-a").claim("clubId", "locale-es"))
                    .authorities(() -> "ROLE_" + role)).header("Accept-Language", "en"))
                    .andExpect(status().isNotFound()).andExpect(header().string("Content-Language", "es"))
                    .andExpect(jsonPath("$.message").value("No se ha encontrado el recurso."));
        }
        mvc.perform(get("/api/v1/missing").with(jwt().jwt(token -> token.subject("account-a").claim("clubId", "locale-ca")))
                .header("Accept-Language", "es"))
                .andExpect(status().isNotFound()).andExpect(header().string("Content-Language", "ca"))
                .andExpect(jsonPath("$.message").value("No s'ha trobat el recurs."));
        mvc.perform(get("/api/v1/missing").with(jwt().jwt(token -> token.subject("account-a")
                .claim("clubId", "locale-ca").claim("locale", "en"))).header("Accept-Language", "es"))
                .andExpect(status().isNotFound()).andExpect(header().string("Content-Language", "en"))
                .andExpect(jsonPath("$.message").value("The resource was not found."));
        assertThat(TenantContext.current()).isNull();
        assertThat(LocaleContext.current()).isEqualTo(java.util.Locale.forLanguageTag("ca"));
    }
    @Test void E0_T08_securityAndTenantFilterErrorsUseTheSameResolver() throws Exception {
        mvc.perform(get("/api/v1/public/missing").header("Host", "locale-es.app.example.test").header("Accept-Language", "en"))
                .andExpect(status().isNotFound()).andExpect(header().string("Content-Language", "es"))
                .andExpect(jsonPath("$.message").value("No se ha encontrado el recurso."));
        mvc.perform(get("/actuator/health").with(jwt().jwt(token -> token.subject("account-a")
                .claim("clubId", "locale-es"))).header("Accept-Language", "en"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("No tienes permiso para realizar esta acción."));
        mvc.perform(get("/api/v1/missing").with(jwt().jwt(token -> token.subject("account-a").claim("locale", "es"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"))
                .andExpect(jsonPath("$.message").value("La cuenta no tiene acceso a este club."));
        mvc.perform(get("/api/v1/branding").header("Host", "unknown.example.test").header("Accept-Language", "es"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"))
                .andExpect(jsonPath("$.message").value("No hay ningún club asociado a este dominio."));
    }
}
