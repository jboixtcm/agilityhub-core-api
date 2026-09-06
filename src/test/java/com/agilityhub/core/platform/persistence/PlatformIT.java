package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.domain.events.ParameterChanged;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Import({PlatformIT.Config.class, PlatformIT.Probe.class})
class PlatformIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs;
    @Autowired ParameterRepository parameters; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired EventPublisher events; @Autowired OutboxDispatcher dispatcher; @Autowired MongoTransactionManager transactions;
    @BeforeEach void prepare() {
        TenantContext.clear();
        mongo.remove(new Query(), Club.class); mongo.remove(new Query(), Parameter.class); mongo.remove(new Query(), DomainEventRecord.class);
        configs.invalidate("club-a"); configs.invalidate("club-b"); hosts.invalidate();
        clubs.save(PlatformFixtures.club("club-a", "app.example.test")); clubs.save(PlatformFixtures.club("club-b", "b.example.test"));
    }
    Parameter parameter(String id, String clubId, String scope, Object value) {
        return new Parameter(id, clubId, "training.capacityPerRingSlot", value, "int", scope == null ? "club" : "ring", scope, List.of(), null, clock.instant());
    }
    @Test void T_02_06_tenantRepositoryCannotReadReplaceDeleteOrInsertAnotherClubsDocumentById() {
        var original = parameter("parameter-a", "club-a", null, 2);
        assertThatThrownBy(() -> parameters.findById(original.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parameters.insert(original)).isInstanceOf(ApiException.class);
        try (var scope = TenantContext.open("club-a")) {
            parameters.insert(original); parameters.insert(parameter("ring-a", "club-a", "ring-small", 3));
            assertThat(parameters.findAll()).hasSize(2); assertThat(parameters.findById(original.id())).isPresent();
            assertThatThrownBy(() -> parameters.insert(parameter("duplicate-a", "club-a", null, 4))).isInstanceOf(DuplicateKeyException.class);
        }
        try (var scope = TenantContext.open("club-b")) {
            assertThat(parameters.findById(original.id())).isEmpty(); assertThat(parameters.findAll()).isEmpty();
            assertThat(parameters.deleteById(original.id())).isFalse();
            assertThatThrownBy(() -> parameters.insert(original)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> parameters.replace(original)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> parameters.replace(parameter(original.id(), "club-b", null, 99))).isInstanceOf(DuplicateKeyException.class);
            parameters.insert(parameter("parameter-b", "club-b", null, 4)); assertThat(parameters.findAll()).hasSize(1);
        }
        try (var scope = TenantContext.open("club-a")) {
            assertThat(parameters.findById(original.id()).orElseThrow().value()).isEqualTo(2);
            parameters.replace(parameter(original.id(), "club-a", null, 5));
            assertThat(parameters.findById(original.id()).orElseThrow().value()).isEqualTo(5);
            assertThat(parameters.deleteById("ring-a")).isTrue();
        }
        System.out.println("clubs indexes: " + mongo.getCollection("clubs").listIndexes().into(new java.util.ArrayList<>()));
        System.out.println("parameters indexes: " + mongo.getCollection("parameters").listIndexes().into(new java.util.ArrayList<>()));
    }
    @Test void T_02_06_uniqueClubIndexesAndVerifiedDomains() {
        assertThatThrownBy(() -> clubs.save(PlatformFixtures.club("club-c", "app.example.test"))).isInstanceOf(DuplicateKeyException.class);
        Document duplicate = mongo.getCollection("clubs").find(new Document("_id", "club-a")).first(); duplicate.put("_id", "club-c"); duplicate.remove("domains");
        assertThatThrownBy(() -> mongo.getCollection("clubs").insertOne(duplicate)).hasMessageContaining("duplicate key");
        assertThat(hosts.resolve("pending.app.example.test")).isEmpty(); assertThat(hosts.resolve("app.example.test")).contains("club-a");
    }
    @Test void T_02_06_hostResolutionMismatchPlatformAndHealth() throws Exception {
        mvc.perform(get("/api/v1/public/tenant").header("Host", "app.example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clubId").value("club-a"));
        mvc.perform(get("/api/v1/branding").header("Host", "unknown.example.test"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        mvc.perform(get("/api/v1/branding").header("Host", "b.example.test").with(jwt().jwt(j -> j.claim("clubId", "club-a"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        mvc.perform(get("/api/v1/platform/tenant").header("Host", "b.example.test").with(jwt().jwt(j -> j.claim("clubId", "club-a"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.global").value(true));
        mvc.perform(get("/api/v1/health").header("Host", "unknown.example.test"))
                .andExpect(status().isOk());
        assertThat(TenantContext.current()).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN"})
    void T_02_07_publicBrandingAndManifestAcceptEveryRoleAndEnforceTenant(String role) throws Exception {
        for (String path : List.of("/api/v1/branding", "/api/v1/manifest.webmanifest")) {
            mvc.perform(get(path).header("Host", "app.example.test").with(jwt().jwt(j -> j.claim("clubId", "club-a"))
                    .authorities(() -> "ROLE_" + role))).andExpect(status().isOk());
            mvc.perform(get(path).header("Host", "b.example.test").with(jwt().jwt(j -> j.claim("clubId", "club-a"))
                    .authorities(() -> "ROLE_" + role))).andExpect(status().isForbidden());
        }
    }
    @Test void T_02_07_brandingHasOnlyPublicFieldsAndSupportsEtagAndManifest() throws Exception {
        var first = mvc.perform(get("/api/v1/branding").header("Host", "app.example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.club.slug").value("club-a"))
                .andExpect(jsonPath("$.theme.colors.primary").exists())
                .andExpect(jsonPath("$.countryProfile.idDocumentTypes[0]").value("DNI"))
                .andExpect(jsonPath("$.countryProfile.idDocumentTypes[1]").value("NIE"))
                .andExpect(jsonPath("$.countryProfile.idDocumentTypes[2]").value("PASSPORT"))
                .andExpect(jsonPath("$.signup.enabled").value(true)).andExpect(jsonPath("$.modules").isArray())
                .andExpect(jsonPath("$.locales[0]").value("ca")).andExpect(jsonPath("$.legal.privacyPolicyUrl").exists())
                .andExpect(header().string("Cache-Control", "max-age=60, public")).andReturn().getResponse();
        assertThat(first.getContentAsString()).doesNotContain("paymentProviders", "secretKeyEnc", "PRIVATE_FIXTURE", "iban", "parameters", "lateCancel", "legalTextsVersion");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(first.getContentAsString());
        assertThat(json.size()).isEqualTo(11);
        mvc.perform(get("/api/v1/branding").header("Host", "app.example.test").header("If-None-Match", first.getHeader("ETag")))
                .andExpect(status().isNotModified()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/branding").header("Host", "app.example.test").header("If-None-Match", "W/" + first.getHeader("ETag")))
                .andExpect(status().isNotModified());
        var manifest = mvc.perform(get("/api/v1/manifest.webmanifest").header("Host", "app.example.test"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/manifest+json"))
                .andExpect(jsonPath("$.name").value("Example Agility Club")).andExpect(jsonPath("$.short_name").value("Example"))
                .andExpect(jsonPath("$.theme_color").exists()).andExpect(jsonPath("$.icons[0].src").exists()).andReturn().getResponse();
        mvc.perform(get("/api/v1/manifest.webmanifest").header("Host", "app.example.test").header("If-None-Match", manifest.getHeader("ETag")))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/manifest.webmanifest").header("Host", "unknown.example.test"))
                .andExpect(status().isNotFound());
    }
    @Test void T_02_01_outboxParameterAndClubEventsInvalidateCaches() throws Exception {
        assertThat(configs.get("club-a").get("signup.enabled", Boolean.class)).isTrue();
        var event = new ParameterChanged("club-a", clock.instant(), Map.of("key", "signup.enabled", "before", true, "after", false),
                "account-a", null, DomainEvent.Origin.BACKOFFICE);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            try (var scope = TenantContext.open("club-a")) {
                parameters.insert(new Parameter("signup-a", "club-a", "signup.enabled", false, "bool", "club", null, List.of(), null, clock.instant()));
                events.publish(event);
            }
        });
        assertThat(configs.get("club-a").get("signup.enabled", Boolean.class)).isTrue();
        dispatcher.dispatch(); assertThat(configs.get("club-a").get("signup.enabled", Boolean.class)).isFalse();
        assertThat(mongo.findAll(DomainEventRecord.class)).singleElement().satisfies(record -> {
            assertThat(record.payload()).containsEntry("key", "signup.enabled").containsEntry("after", false);
            assertThat(record.status()).isEqualTo(DomainEventRecord.Status.PUBLISHED);
        });
        assertThat(hosts.resolve("new.example.test")).isEmpty(); assertThat(hosts.resolve("app.example.test")).contains("club-a");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            mongo.updateFirst(Query.query(Criteria.where("_id").is("club-a")), new Update().set("domains.0.host", "new.example.test")
                    .set("status", "SUSPENDED"), Club.class);
            events.publish(new ClubConfigChanged("club-a", clock.instant(), Map.of("diff", Map.of("status", "SUSPENDED")),
                    "account-a", null, DomainEvent.Origin.BACKOFFICE));
        });
        dispatcher.dispatch(); assertThat(hosts.resolve("new.example.test")).contains("club-a"); assertThat(hosts.resolve("app.example.test")).isEmpty();
        mvc.perform(get("/api/v1/branding").header("Host", "new.example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.signup.enabled").value(false));
    }
    @TestConfiguration(proxyBeanMethods = false) static class Config {
        @Bean @Order(0) SecurityFilterChain probes(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/public/tenant", "/api/v1/platform/tenant")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }
    @RestController static class Probe {
        @GetMapping("/api/v1/public/tenant") Map<String, String> tenant() { return Map.of("clubId", TenantContext.require()); }
        @GetMapping("/api/v1/platform/tenant") Map<String, Boolean> global() { return Map.of("global", TenantContext.current() == null); }
    }
}
