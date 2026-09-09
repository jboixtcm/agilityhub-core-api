package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SettingsIT extends AbstractIntegrationTest {
    static final String HOST = "settings-a.example.test";
    static final String OTHER = "settings-b.example.test";
    static final String KEY = "bookings.lateCancelThresholdMinutes";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired ParameterRepository parameters;
    @Autowired ParameterSettingsService settings;
    @Autowired ModuleDependencyValidator dependencies;
    @Autowired MongoTransactionManager transactions;

    @BeforeEach void seed() {
        TenantContext.clear();
        for (Class<?> type : List.of(Club.class, Parameter.class, AuditEntry.class, DomainEventRecord.class)) { mongo.remove(new Query(), type); }
        mongo.remove(new Query(), "class_sessions");
        clubs.save(PlatformFixtures.club("settings-a", HOST));
        clubs.save(PlatformFixtures.club("settings-b", OTHER, "America/Argentina/Buenos_Aires"));
        configs.invalidate("settings-a"); configs.invalidate("settings-b"); hosts.invalidate();
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, "settings-a", HOST, "ADMIN"); }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String host, String role) throws Exception {
        return mvc.perform(request.header("Host", host).with(jwt().jwt(j -> j.subject("settings-admin")
                .claim("name", "Example Admin").claim("clubId", club)).authorities(() -> "ROLE_" + role)));
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(body));
    }
    JsonNode json(ResultActions result) throws Exception { return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    JsonNode update(String key, Object value, long version) throws Exception {
        return json(admin(body(put("/api/v1/parameters/" + key), Map.of("value", value, "version", version))));
    }
    List<AuditEntry> audits() { return mongo.findAll(AuditEntry.class); }
    List<DomainEventRecord> events() { return mongo.findAll(DomainEventRecord.class); }

    @Test @AuditCovers(AuditAction.PARAMETER_CHANGED)
    void T_02_08_parameterHistoryAuditOutboxCacheAndVersionedReset() throws Exception {
        assertThat(configs.get("settings-a").get(KEY, Integer.class)).isEqualTo(240);
        var first = json(admin(body(put("/api/v1/parameters/" + KEY), Map.of("value", 120, "version", 0, "reason", "Example policy"))));
        assertThat(first.path("version").asLong()).isEqualTo(1);
        assertThat(first.path("default").asInt()).isEqualTo(240);
        assertThat(first.path("history").get(0).path("value").asInt()).isEqualTo(240);
        assertThat(first.path("lastChange").path("actorName").asText()).isEqualTo("Example Admin");
        assertThat(configs.get("settings-a").get(KEY, Integer.class)).isEqualTo(120);
        assertThat(configs.get("settings-b").get(KEY, Integer.class)).isEqualTo(240);
        assertThat(audits()).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo(AuditAction.PARAMETER_CHANGED);
            assertThat(entry.changes()).contains(new AuditChange("value", 240, 120));
            assertThat(entry.reason()).isEqualTo("Example policy");
            assertThat(entry.actorAccountId()).isEqualTo("settings-admin");
        });
        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("ParameterChanged");
            assertThat(event.payload()).containsEntry("before", 240).containsEntry("after", 120).containsEntry("scopeRef", null);
        });
        admin(body(put("/api/v1/parameters/" + KEY), Map.of("value", 300, "version", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        clock.advance(Duration.ofSeconds(1));
        update(KEY, 240, 1);
        var history = json(admin(get("/api/v1/parameters/" + KEY + "/history")));
        assertThat(history).hasSize(2);
        assertThat(history.get(1).path("value").asInt()).isEqualTo(120);
        assertThat(history.get(0).path("changedByAccountId").asText()).isEqualTo("settings-admin");
        assertThat(history.get(0).path("reason").asText()).isEqualTo("Example policy");
        assertThat(history.get(0).path("changedAt").asText()).isNotBlank();
        var list = json(admin(get("/api/v1/parameters").param("block", "classes")));
        assertThat(list.path("lastChange").path("action").asText()).isEqualTo("PARAMETER_CHANGED");
        assertThat(list.path("blocks").get(0).path("rows").findValuesAsText("key")).contains(KEY);
        clock.advance(Duration.ofSeconds(1));
        var reset = json(admin(delete("/api/v1/parameters/" + KEY)));
        assertThat(reset.path("isOverride").asBoolean()).isFalse();
        assertThat(reset.path("value").asInt()).isEqualTo(240);
        assertThat(reset.path("version").asLong()).isEqualTo(3);
        assertThat(reset.path("history")).hasSize(3);
        assertThat(configs.get("settings-a").get(KEY, Integer.class)).isEqualTo(240);
        assertThat(json(admin(delete("/api/v1/parameters/" + KEY))).path("version").asLong()).isEqualTo(3);
        admin(body(put("/api/v1/parameters/" + KEY), Map.of("value", 300, "version", 2))).andExpect(status().isConflict());
        assertThat(events()).hasSize(3);
        assertThat(json(admin(get("/api/v1/parameters").param("block", "missing"))).path("blocks")).isEmpty();
        assertThat(json(admin(get("/api/v1/parameters"))).path("blocks").size()).isGreaterThan(5);
    }

    @Test void T_02_08_scopedResetFallsBackToClubAndInvalidStoredValuesUseDefaults() throws Exception {
        String key = "training.capacityPerRingSlot";
        update(key, 2, 0);
        var scoped = json(admin(body(put("/api/v1/parameters/" + key), Map.of("value", 3, "version", 0, "scopeRef", "ring:small"))));
        assertThat(scoped.path("value").asInt()).isEqualTo(3);
        assertThat(configs.get("settings-a").get(key, "ring:small", Integer.class)).isEqualTo(3);
        var reset = json(admin(delete("/api/v1/parameters/" + key).param("scopeRef", "ring:small")));
        assertThat(reset.path("value").asInt()).isEqualTo(2);
        assertThat(reset.path("isOverride").asBoolean()).isFalse();
        assertThat(configs.get("settings-a").get(key, "ring:small", Integer.class)).isEqualTo(2);
        assertThat(json(admin(get("/api/v1/parameters/" + key + "/history").param("scopeRef", "ring:small")))).hasSize(2);
        try (var scope = TenantContext.open("settings-a")) {
            parameters.insert(new Parameter("invalid", "settings-a", KEY, "wrong", "duration", "club", null, List.of(), 1L, clock.instant()));
        }
        assertThat(json(admin(get("/api/v1/parameters/" + KEY))).path("value").asInt()).isEqualTo(240);
        assertThat(json(admin(delete("/api/v1/parameters/signup.enabled"))).path("version").asLong()).isZero();
        assertThat(json(admin(get("/api/v1/parameters/billing.entryFeePerDog"))).path("default").path("currency").asText()).isEqualTo("EUR");
    }

    @Test void T_02_08_parameterValidationAndEditGuardsRejectWithoutWrites() throws Exception {
        for (Object invalid : List.of("120", -1, 1.5, Map.of())) {
            admin(body(put("/api/v1/parameters/" + KEY), Map.of("value", invalid, "version", 0)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETER_INVALID"));
        }
        admin(put("/api/v1/parameters/" + KEY).contentType("application/json").content("{\"value\":null,\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETER_INVALID"));
        for (var request : List.of(get("/api/v1/parameters/unknown"), get("/api/v1/parameters/unknown/history"),
                body(put("/api/v1/parameters/unknown"), Map.of("value", 1, "version", 0)), delete("/api/v1/parameters/unknown"))) {
            admin(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"));
        }
        for (var request : List.of(body(put("/api/v1/parameters/auth.sessionDays"), Map.of("value", 1, "version", 0)), delete("/api/v1/parameters/auth.sessionDays"))) {
            admin(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_ONLY"));
        }
        for (String scope : List.of("ring:small", " ")) {
            admin(body(put("/api/v1/parameters/" + KEY), Map.of("value", 120, "version", 0, "scopeRef", scope)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETER_INVALID"));
        }
        for (var request : List.of(body(put("/api/v1/parameters/courses.showSetupToMembers"), Map.of("value", false, "version", 0)),
                delete("/api/v1/parameters/courses.showSetupToMembers"))) {
            admin(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        }
        admin(body(put("/api/v1/parameters/signup.text.closed"), Map.of("value", Map.of("en", "Example"), "version", 0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETER_INVALID"));
        assertThat(audits()).isEmpty(); assertThat(events()).isEmpty();
    }

    @Test void T_02_08_concurrentEditorsProduceOneChangeAndStaleResponses() throws Exception {
        for (int round = 0; round < 2; round++) {
            long version = json(admin(get("/api/v1/parameters/" + KEY))).path("version").asLong();
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
                var jobs = new ArrayList<java.util.concurrent.Future<Integer>>();
                for (int i = 0; i < 6; i++) {
                    int value = 300 + 100 * round + i;
                    jobs.add(pool.submit(() -> {
                        start.await();
                        return admin(body(put("/api/v1/parameters/" + KEY), Map.of("version", version, "value", value)))
                                .andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                var statuses = new ArrayList<Integer>();
                for (var job : jobs) { statuses.add(job.get(30, java.util.concurrent.TimeUnit.SECONDS)); }
                assertThat(statuses).containsOnly(200, 409);
                assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
            }
        }
        assertThat(audits()).hasSize(2); assertThat(events()).hasSize(2);
    }

    @Test void T_02_08_auditFailureRollsBackParameterOutboxHistoryAndCache() {
        assertThat(configs.get("settings-a").get(KEY, Integer.class)).isEqualTo(240);
        mongo.executeCommand(new Document("collMod", "audit_entries").append("validator", new Document("action", new Document("$ne", "PARAMETER_CHANGED"))));
        try (var scope = TenantContext.open("settings-a")) {
            assertThatThrownBy(() -> settings.update(KEY, 120, null, 0L, "Example"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(parameters.findAll()).isEmpty();
            assertThat(audits()).isEmpty(); assertThat(events()).isEmpty();
            assertThat(configs.get("settings-a").get(KEY, Integer.class)).isEqualTo(240);
        } finally { mongo.executeCommand(new Document("collMod", "audit_entries").append("validator", new Document())); }
        try (var scope = TenantContext.open("settings-a")) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                settings.update(KEY, 120, null, 0L, "Example");
                tx.setRollbackOnly();
            });
            assertThat(parameters.findAll()).isEmpty(); assertThat(events()).isEmpty(); assertThat(audits()).isEmpty();
        }
    }

    @Test @AuditCovers(AuditAction.CLUB_MODULES_CHANGED)
    void T_02_09_selfServiceModulesAuditOutboxDependenciesAndBranding() throws Exception {
        for (String key : List.of("PUSH", "FAQ", "LEARN_LINK")) {
            for (boolean enabled : List.of(true, false, false, true)) {
                var result = json(admin(body(put("/api/v1/club/modules/" + key), Map.of("enabled", enabled))));
                assertThat(result.path("modules").toString().contains('"' + key + '"')).isEqualTo(enabled);
                var branding = json(mvc.perform(get("/api/v1/branding").header("Host", HOST)));
                assertThat(branding.path("modules").toString().contains('"' + key + '"')).isEqualTo(enabled);
            }
        }
        assertThat(audits()).allMatch(entry -> entry.action() == AuditAction.CLUB_MODULES_CHANGED);
        assertThat(events()).allMatch(event -> event.type().equals("ClubModulesChanged"));
        assertThat(audits()).hasSize(events().size()).isNotEmpty();
        for (String key : List.of("BILLING", "PACKS", "invalid")) {
            admin(body(put("/api/v1/club/modules/" + key), Map.of("enabled", true)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_ONLY"));
        }
        assertThatThrownBy(() -> dependencies.validateChange(Set.of(), Module.PACKS, true)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.MODULE_DEPENDENCY));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("settings-a")), new Update().set("modules", List.of("PACKS")), Club.class);
        admin(body(put("/api/v1/club/modules/FAQ"), Map.of("enabled", true)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY"));
    }

    @Test @AuditCovers(AuditAction.CLUB_UPDATED)
    void T_02_11_clubProjectionAndAllowlistedUpdatesAuditAndRefreshBranding() throws Exception {
        var before = json(admin(get("/api/v1/club")));
        assertThat(before.toString()).doesNotContain("PRIVATE_FIXTURE", "secretKeyEnc", "webhookSecretEnc", "iban", "onboardingChecklist", "usage");
        assertThat(before.at("/paymentProviders/STRIPE/configured").asBoolean()).isTrue();
        var update = new LinkedHashMap<String, Object>();
        update.put("version", before.path("version").asLong()); update.put("name", "Renamed Example Club");
        update.put("contactEmail", "settings@example.test"); update.put("contactPhone", "+34930000000");
        update.put("websiteUrl", "https://settings.example.test"); update.put("legalName", "Example Association");
        update.put("taxId", "EXAMPLE-ORG"); update.put("address", Map.of("city", "Example City"));
        var theme = (com.fasterxml.jackson.databind.node.ObjectNode) before.path("theme").deepCopy();
        theme.put("mode", "dark"); update.put("theme", theme);
        var after = json(admin(body(put("/api/v1/club"), update)));
        assertThat(after.path("name").asText()).isEqualTo("Renamed Example Club");
        assertThat(after.path("version").asLong()).isEqualTo(before.path("version").asLong() + 1);
        assertThat(after.path("lastChange").path("action").asText()).isEqualTo("CLUB_UPDATED");
        assertThat(json(mvc.perform(get("/api/v1/branding").header("Host", HOST))).at("/theme/mode").asText()).isEqualTo("dark");
        assertThat(audits()).singleElement().satisfies(entry -> assertThat(entry.changes()).contains(new AuditChange("settings.name", "Example Agility Club", "Renamed Example Club")));
        assertThat(events()).singleElement().satisfies(event -> assertThat(event.type()).isEqualTo("ClubUpdated"));
        admin(body(put("/api/v1/club"), update)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        for (String field : List.of("slug", "domains", "paymentProviders", "currency", "locales", "status", "legal", "modules")) {
            admin(body(put("/api/v1/club"), Map.of("version", after.path("version").asLong(), field, "forbidden")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_ONLY"));
        }
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("unknown", "value"), Map.of("name", " "), Map.of("name", 5),
                Map.of("contactEmail", "wrong"), Map.of("theme", Map.of()), Map.of("theme", Map.of("colors", Map.of(), "fontFamily", "Example", "radius", "4px", "ringPalette", List.of(), "mode", "dark")))) {
            var request = new LinkedHashMap<>(invalid); request.put("version", after.path("version").asLong());
            admin(body(put("/api/v1/club"), request)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        admin(body(put("/api/v1/club"), Map.of("version", 1.5))).andExpect(status().isConflict());
        admin(body(put("/api/v1/club"), Map.of())).andExpect(status().isConflict());
    }

    @Test void T_02_15_timezoneChangeBlockedByTenantClassesAndWeekOpeningStaysLocal() throws Exception {
        mongo.insert(new Document("_id", "class-b").append("clubId", "settings-b"), "class_sessions");
        admin(body(put("/api/v1/club"), Map.of("version", 0, "timeZone", "UTC")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_ONLY"));
        mongo.insert(new Document("_id", "class-a").append("clubId", "settings-a"), "class_sessions");
        admin(body(put("/api/v1/club"), Map.of("version", 0, "timeZone", "UTC")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TIMEZONE_CHANGE_BLOCKED"));
        admin(body(put("/api/v1/club"), Map.of("version", 0, "timeZone", "Europe/Madrid")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_ONLY"));
        var opening = Map.of("dayOfWeek", "SUNDAY", "time", "20:00");
        call(body(put("/api/v1/parameters/bookings.weekOpensAt"), Map.of("value", opening, "version", 0)), "settings-b", OTHER, "ADMIN")
                .andExpect(status().isOk()).andExpect(jsonPath("$.value.time").value("20:00"));
        var config = configs.get("settings-b");
        assertThat(config.get("bookings.weekOpensAt", Map.class)).isEqualTo(opening);
        assertThat(LocalDate.of(2030, 1, 6).atTime(LocalTime.parse((String) config.get("bookings.weekOpensAt", Map.class).get("time")))
                .atZone(configs.timeZone("settings-b")).toInstant()).isEqualTo(Instant.parse("2030-01-06T23:00:00Z"));
    }

    @Test void T_02_16_openingHoursAndLabeledHolidaysAliasesShareVersionedParameters() throws Exception {
        for (String[] times : List.of(new String[]{"22:00", "22:00"}, new String[]{"23:00", "22:00"}, new String[]{"07:01", "22:00"}, new String[]{"07:00", "24:00"})) {
            admin(body(put("/api/v1/club/opening-hours"), Map.of("value", Map.of("MONDAY", Map.of("open", times[0], "close", times[1])), "version", 0)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETER_INVALID"));
        }
        var hours = Map.of("MONDAY", Map.of("open", "07:00", "close", "22:00"));
        admin(body(put("/api/v1/club/opening-hours"), Map.of("value", hours, "version", 0))).andExpect(status().isOk());
        assertThat(json(admin(get("/api/v1/club/opening-hours"))).path("value")).isEqualTo(mapper.valueToTree(hours));
        admin(body(put("/api/v1/club/opening-hours"), Map.of("value", hours, "version", 0))).andExpect(status().isConflict());
        var holidays = List.of(Map.of("date", "2030-12-25", "label", "Example holiday"));
        admin(body(put("/api/v1/club/holidays"), Map.of("value", holidays, "version", 0, "reason", "Example closure"))).andExpect(status().isOk());
        assertThat(json(admin(get("/api/v1/club/holidays"))).path("value")).isEqualTo(mapper.valueToTree(holidays));
        assertThat(configs.get("settings-a").get("club.holidays", List.class)).isEqualTo(holidays);
        assertThat(configs.get("settings-a").get("club.openingHours", Map.class)).isEqualTo(hours);
        admin(body(put("/api/v1/club/holidays"), Map.of("value", List.of(Map.of("date", "2030-12-25", "label", "")), "version", 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETER_INVALID"));
        admin(body(put("/api/v1/club/holidays"), Map.of("value", List.of("2030-12-25"), "version", 1))).andExpect(status().isBadRequest());
        admin(body(put("/api/v1/parameters/club.holidays"), Map.of("value", List.of("2030-12-25"), "version", 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETER_INVALID"));
    }

    List<MockHttpServletRequestBuilder> adminRoutes() throws Exception {
        return List.of(get("/api/v1/club"), body(put("/api/v1/club"), Map.of("version", 0, "name", "Example")),
                get("/api/v1/parameters"), get("/api/v1/parameters/" + KEY), get("/api/v1/parameters/" + KEY + "/history"),
                body(put("/api/v1/parameters/" + KEY), Map.of("version", 0, "value", 120)), delete("/api/v1/parameters/" + KEY),
                body(put("/api/v1/club/modules/PUSH"), Map.of("enabled", false)), get("/api/v1/club/opening-hours"), get("/api/v1/club/holidays"),
                body(put("/api/v1/club/opening-hours"), Map.of("version", 0, "value", Map.of())), body(put("/api/v1/club/holidays"), Map.of("version", 0, "value", List.of())));
    }
    @ParameterizedTest @ValueSource(strings = {"MEMBER", "INSTRUCTOR", "AGILITYHUB_ADMIN"})
    void T_02_11_everyAdminRouteEnforcesRoles(String role) throws Exception {
        for (var request : adminRoutes()) { call(request, "settings-a", HOST, role).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN")); }
    }
    @Test void T_02_11_allSettingsRoutesRejectAnonymousAndCrossTenantRequests() throws Exception {
        for (var request : adminRoutes()) { mvc.perform(request.header("Host", HOST)).andExpect(status().isUnauthorized()); }
        for (var request : adminRoutes()) { call(request, "settings-a", OTHER, "ADMIN").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH")); }
        update(KEY, 120, 0);
        assertThat(json(call(get("/api/v1/parameters/" + KEY), "settings-b", OTHER, "ADMIN")).path("value").asInt()).isEqualTo(240);
        assertThat(json(call(get("/api/v1/parameters/" + KEY + "/history"), "settings-b", OTHER, "ADMIN"))).isEmpty();
        assertThat(json(call(get("/api/v1/parameters"), "settings-b", OTHER, "ADMIN")).path("lastChange").isNull()).isTrue();
        assertThat(json(call(get("/api/v1/club"), "settings-b", OTHER, "ADMIN")).path("id").asText()).isEqualTo("settings-b");
    }
    @Test void T_02_11_countryProfilesArePublicTenantBoundAndCatalogIsPlatformOnly() throws Exception {
        for (String path : List.of("/api/v1/country-profile", "/api/v1/country-profile/postal-codes/08349")) {
            mvc.perform(get(path).header("Host", HOST)).andExpect(status().isOk());
            mvc.perform(get(path).header("Host", "unknown.example.test")).andExpect(status().isNotFound());
            for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN")) {
                call(get(path), "settings-a", HOST, role).andExpect(status().isOk());
                call(get(path), "settings-a", OTHER, role).andExpect(status().isForbidden());
            }
        }
        assertThat(json(mvc.perform(get("/api/v1/country-profile/postal-codes/08349").header("Host", HOST))).get(0).path("town").asText()).isEqualTo("Cabrera de Mar");
        var es = json(mvc.perform(get("/api/v1/country-profile").header("Host", HOST)));
        assertThat(es.path("phonePrefix").asText()).isEqualTo("+34"); assertThat(es.path("dateFormat").asText()).isEqualTo("dd/MM/yyyy");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("settings-b")), new Update().set("countryProfile", "GENERIC"), Club.class);
        configs.invalidate("settings-b");
        assertThat(json(mvc.perform(get("/api/v1/country-profile/postal-codes/08349").header("Host", OTHER)))).isEmpty();
        assertThat(json(mvc.perform(get("/api/v1/country-profile").header("Host", OTHER))).path("postalCodeLookup").asBoolean()).isFalse();
        String path = "/api/v1/platform/parameter-catalog";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : List.of("ADMIN", "MEMBER", "INSTRUCTOR")) { call(get(path), "settings-a", HOST, role).andExpect(status().isForbidden()); }
        var catalog = json(mvc.perform(get(path).header("Host", "id.example.test").with(jwt().authorities(() -> "ROLE_AGILITYHUB_ADMIN"))));
        assertThat(catalog.findValuesAsText("key")).contains(KEY, "auth.sessionDays");
        call(get(path), "settings-a", OTHER, "AGILITYHUB_ADMIN").andExpect(status().isOk());
        mvc.perform(get(path).with(jwt().jwt(j -> j.claim("imp", true)).authorities(() -> "ROLE_AGILITYHUB_ADMIN"))).andExpect(status().isForbidden());
    }
}
