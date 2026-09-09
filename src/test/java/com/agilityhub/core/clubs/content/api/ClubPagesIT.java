package com.agilityhub.core.clubs.content.api;

import com.agilityhub.core.clubs.content.application.*;
import com.agilityhub.core.clubs.content.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.audit.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ClubPagesIT extends AbstractIntegrationTest {
    static final String CLUB = "pages-a", OTHER = "pages-b", BASE = "/api/v1/club-pages";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired ClubPageService pages;
    @Autowired ClubPageRepository repository;
    @Autowired ClubPageSeeds seeds;
    @Autowired ClubDefinitions definitions;
    @Autowired ClubDefinitionCodec codec;
    @Autowired MongoTransactionManager transactions;
    @MockitoSpyBean AuditRepository audits;
    String publicKey;
    @BeforeEach void seed() {
        reset(audits); TenantContext.clear(); clock.setInstant(Instant.parse("2026-01-15T12:00:00Z"));
        for (String collection : List.of("clubs", "parameters", "club_pages", "audit_entries", "domain_events", "memberships", "accounts")) { mongo.remove(new Query(), collection); }
        publicKey = UUID.randomUUID().toString();
        for (String id : List.of(CLUB, OTHER)) {
            var club = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id + ".example.test"));
            club.put("publicApiKeyHash", PublicClubAccess.digest(id.equals(CLUB) ? publicKey : UUID.randomUUID().toString()));
            clubs.save(mapper.convertValue(club, Club.class)); configs.invalidate(id);
        }
        hosts.invalidate();
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject("page-admin")
                .claim("name", "Example Page Admin").claim("clubId", club)).authorities(() -> "ROLE_" + role)));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "ADMIN"); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(value));
    }
    JsonNode json(ResultActions response, int code) throws Exception {
        return mapper.readTree(response.andExpect(status().is(code)).andReturn().getResponse().getContentAsString());
    }
    Map<String, Object> input(String key, boolean active) {
        return Map.of("key", key, "title", Map.of("ca", "Normes", "es", "Normas"), "body", Map.of("ca", "# Regles", "es", "# Reglas"), "active", active);
    }
    JsonNode create(String key, boolean active) throws Exception { return json(admin(body(post(BASE), input(key, active))), 201); }
    JsonNode change(String key, Object input) throws Exception { return json(admin(body(patch(BASE + "/" + key), input)), 200); }
    void error(ResultActions result, int status, String code) throws Exception { result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)); }
    @Test void T_05_CP_01_crudPublicationAndTenImmutablePublishedBodies() throws Exception {
        var draft = create("RULES", false);
        assertThat(draft.path("version").asInt()).isEqualTo(1); assertThat(draft.get("publishedAt").isNull()).isTrue();
        var edited = change("RULES", Map.of("version", 1, "body", Map.of("ca", "Draft two")));
        assertThat(edited.path("version").asInt()).isEqualTo(1); assertThat(edited.get("publishedAt").isNull()).isTrue();
        var active = change("RULES", Map.of("version", 1, "active", true));
        assertThat(active.path("version").asInt()).isEqualTo(2); assertThat(active.path("publishedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(active.path("history")).isEmpty();
        var title = change("RULES", Map.of("version", 2, "title", Map.of("ca", "Changed title")));
        assertThat(title.path("version").asInt()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(1));
        var version3 = change("RULES", Map.of("version", 2, "body", Map.of("ca", "Version three")));
        assertThat(version3.path("version").asInt()).isEqualTo(3);
        assertThat(version3.at("/history/0/body/ca").asText()).isEqualTo("Draft two");
        assertThat(version3.at("/history/0/by").asText()).isEqualTo("page-admin");
        for (int version = 3; version < 15; version++) {
            clock.advance(Duration.ofSeconds(1));
            change("RULES", Map.of("version", version, "body", Map.of("ca", "Version " + (version + 1))));
        }
        var last = json(admin(get(BASE + "/RULES")), 200);
        assertThat(last.path("history")).hasSize(10);
        assertThat(last.at("/history/0/version").asInt()).isEqualTo(5); assertThat(last.at("/history/9/version").asInt()).isEqualTo(14);
        var disabled = change("RULES", Map.of("version", 15, "active", false, "body", Map.of("ca", "Private draft")));
        assertThat(disabled.at("/history/9/body/ca").asText()).isEqualTo("Version 15");
        change("RULES", Map.of("version", 15, "body", Map.of("ca", "Another private draft")));
        var republished = change("RULES", Map.of("version", 15, "active", true));
        assertThat(republished.path("version").asInt()).isEqualTo(16);
        assertThat(republished.at("/history/9/body/ca").asText()).isEqualTo("Version 15");
        assertThat(republished.path("history")).hasSize(10);
        assertThat(create("WELCOME_GUIDE", true).path("version").asInt()).isEqualTo(1);
        error(admin(body(post(BASE), input("RULES", false))), 409, "DUPLICATE_NAME");
    }
    @Test void T_05_CP_02_allEndpointsEnforceTenantRolesAndHideDrafts() throws Exception {
        create("RULES", true); create("PRIVACY", false);
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            for (String active : List.of("true", "false")) {
                var list = json(call(get(BASE).param("active", active), CLUB, role), 200);
                assertThat(list.path("items").findValuesAsText("key")).containsExactly("RULES");
                assertThat(list.toString()).doesNotContain("page-admin", "history");
            }
            call(get(BASE + "/RULES"), CLUB, role).andExpect(status().isOk());
            error(call(get(BASE + "/PRIVACY"), CLUB, role), 404, "NOT_FOUND");
            for (var request : List.of(body(post(BASE), input("free-page", true)), body(patch(BASE + "/RULES"), Map.of("version", 1)))) {
                call(request, CLUB, role).andExpect(status().isForbidden());
            }
        }
        assertThat(json(admin(get(BASE)), 200).path("items")).hasSize(2);
        assertThat(json(admin(get(BASE).param("active", "false")), 200).path("items").findValuesAsText("key")).containsExactly("PRIVACY");
        assertThat(json(admin(get(BASE).param("active", "true")), 200).path("items")).hasSize(1);
        assertThat(json(call(get(BASE), OTHER, "ADMIN"), 200).path("items")).isEmpty();
        for (var request : List.of(get(BASE), get(BASE + "/RULES"), body(post(BASE), input("free-page", true)), body(patch(BASE + "/RULES"), Map.of("version", 1)))) {
            mvc.perform(request.header("Host", CLUB + ".example.test")).andExpect(status().isUnauthorized());
            call(request, CLUB, "AGILITYHUB_ADMIN").andExpect(status().isForbidden());
            error(mvc.perform(request.header("Host", CLUB + ".example.test").with(jwt().jwt(j -> j.claim("clubId", OTHER)).authorities(() -> "ROLE_ADMIN"))), 403, "TENANT_MISMATCH");
        }
        for (var request : List.of(get(BASE + "/RULES"), body(patch(BASE + "/RULES"), Map.of("version", 1)))) { error(call(request, OTHER, "ADMIN"), 404, "NOT_FOUND"); }
        call(body(post(BASE), input("RULES", true)), OTHER, "ADMIN").andExpect(status().isCreated());
        try (var scope = TenantContext.open(OTHER)) {
            var page = repository.findByKey("RULES").orElseThrow();
            assertThat(page.clubId()).isEqualTo(OTHER);
            assertThatThrownBy(() -> repository.update(page, -1)).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_05_CP_03_publicKeyLocaleFallbackDraftsAndHostIndependence() throws Exception {
        create("RULES", true); create("PRIVACY", false);
        String path = "/api/v1/public/" + CLUB + "/pages/RULES";
        error(mvc.perform(get(path)), 403, "INVALID_API_KEY");
        error(mvc.perform(get(path).header("X-Api-Key", "invalid")), 403, "INVALID_API_KEY");
        error(mvc.perform(get("/api/v1/public/" + OTHER + "/pages/RULES").header("X-Api-Key", publicKey)), 403, "INVALID_API_KEY");
        error(mvc.perform(get("/api/v1/public/absent/pages/RULES").header("X-Api-Key", publicKey)), 404, "CLUB_NOT_FOUND");
        for (String language : List.of("es", "es-ES,ca;q=0.5", "fr;q=1,es;q=0.8")) {
            mvc.perform(get(path).header("Host", OTHER + ".example.test").header("X-Api-Key", publicKey).header("Accept-Language", language))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("# Reglas")).andExpect(header().string("Content-Language", "es"));
        }
        for (String language : List.of("fr", "malformed;", "ca;q=1,es;q=0")) {
            mvc.perform(get(path).header("X-Api-Key", publicKey).header("Accept-Language", language)).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("# Regles"));
        }
        assertThat(json(mvc.perform(get(path).header("X-Api-Key", publicKey)), 200).toString()).doesNotContain("lastChange", "history", "page-admin");
        change("RULES", Map.of("version", 1, "body", Map.of("ca", "Default only")));
        mvc.perform(get(path).header("X-Api-Key", publicKey).header("Accept-Language", "es")).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("Default only"));
        for (String key : List.of("PRIVACY", "missing")) { error(mvc.perform(get("/api/v1/public/" + CLUB + "/pages/" + key).header("X-Api-Key", publicKey)), 404, "NOT_FOUND"); }
    }
    @Test void T_05_CP_04_staleAndInvalidUpdatesLeavePagesUnchanged() throws Exception {
        create("RULES", true);
        error(admin(body(patch(BASE + "/RULES"), Map.of("version", 9, "active", false))), 409, "STALE_VERSION");
        for (Object input : List.of(Map.of(), Map.of("version", 0), Map.of("version", 1, "body", Map.of("ca", "![bad](/photo)")),
                Map.of("version", 1, "body", Map.of("ca", "x".repeat(20001))), Map.of("version", 1, "title", Map.of("es", "Missing default")))) {
            error(admin(body(patch(BASE + "/RULES"), input)), 400, "VALIDATION_ERROR");
        }
        error(admin(body(post(BASE), Map.of())), 400, "VALIDATION_ERROR");
        assertThat(mongo.count(new Query(), "audit_entries")).isEqualTo(1);
        assertThat(json(admin(get(BASE + "/RULES")), 200).path("version").asInt()).isEqualTo(1);
    }
    @Test @AuditCovers(AuditAction.CATALOG_CHANGED)
    void T_05_CP_05_auditOutboxAndPageWritesCommitAndRollbackTogether() throws Exception {
        create("RULES", true); change("RULES", Map.of("version", 1));
        var audit = mongo.findAll(AuditEntry.class);
        assertThat(audit).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.entityType()).isEqualTo("ClubPage"); assertThat(entry.entityId()).isEqualTo("RULES");
            assertThat(entry.action()).isEqualTo(AuditAction.CATALOG_CHANGED);
            assertThat(entry.changes()).extracting(AuditChange::path).containsExactlyInAnyOrder("details.version", "details.active");
        });
        assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(2).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo("ClubPageChanged"); assertThat(event.clubId()).isEqualTo(CLUB);
            assertThat(event.payload()).containsEntry("key", "RULES").containsEntry("version", 1).containsEntry("active", true);
        });
        doThrow(new IllegalStateException("Injected audit failure")).when(audits).append(any());
        try (var scope = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> pages.update("RULES", null, Map.of("ca", "Will roll back"), null, 1)).isInstanceOf(IllegalStateException.class);
            assertThat(pages.get("RULES", true).body().values().get("ca")).isEqualTo("# Regles");
        }
        reset(audits);
        try (var scope = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                pages.create("rolled-back", Map.of("ca", "Title"), Map.of("ca", "Body"), false); throw new IllegalStateException("Outer rollback");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(mongo.findAll(ClubPage.class)).hasSize(1); assertThat(mongo.findAll(AuditEntry.class)).hasSize(2);
        assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(2);
    }
    @Test void T_05_CP_04_concurrentPublicationCannotOverwriteAnotherVersion() throws Exception {
        create("RULES", true); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int n = i;
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return admin(body(patch(BASE + "/RULES"), Map.of("version", 1, "body", Map.of("ca", "Concurrent " + n)))).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(2);
    }
    @Test void T_05_CP_06_seedCreatesThreePagesAndSecondApplyMakesNoWrites() throws Exception {
        var definition = codec.read(Path.of("seeds/club-canic.yaml")); definition.remove("accounts");
        var preview = definitions.apply(definition, true); assertThat(preview.render(true)).contains("3 page changes");
        assertThat(mongo.findAll(ClubPage.class)).isEmpty();
        var first = definitions.apply(definition, false); assertThat(first.render(false)).contains("3 page changes");
        var saved = mongo.findAll(ClubPage.class); assertThat(saved).hasSize(3);
        for (var page : saved) {
            assertThat(page.body().values().values()).allMatch(body -> body.startsWith(ProvisionalText.MARKER));
            assertThat(page.version()).isEqualTo(1); assertThat(page.active()).isEqualTo(!page.key().equals("PRIVACY"));
        }
        var auditsBefore = mongo.findAll(Document.class, "audit_entries"); var eventsBefore = mongo.findAll(Document.class, "domain_events");
        var second = definitions.apply(definition, false); assertThat(second.changes()).isZero(); assertThat(second.render(false)).contains("0 page changes");
        assertThat(mongo.findAll(ClubPage.class)).containsExactlyInAnyOrderElementsOf(saved);
        assertThat(mongo.findAll(Document.class, "audit_entries")).containsExactlyInAnyOrderElementsOf(auditsBefore);
        assertThat(mongo.findAll(Document.class, "domain_events")).containsExactlyInAnyOrderElementsOf(eventsBefore);
        var exported = definitions.export("canic"); assertThat(exported.path("pages")).hasSize(3);
        assertThat(definitions.apply(exported, false).changes()).isZero();
        ((ObjectNode) definition.path("pages").get(0).path("body")).put("ca", "Revised seed");
        assertThat(definitions.apply(definition, true).render(true)).contains("1 page changes");
        definitions.apply(definition, false);
        try (var scope = TenantContext.open(first.id())) {
            assertThat(pages.get("RULES", true).version()).isEqualTo(2);
            var same = seeds.list().getFirst();
            seeds.provision(same, "ca", List.of("ca", "es"));
        }
        ((com.fasterxml.jackson.databind.node.ArrayNode) definition.path("pages")).add(definition.path("pages").get(0).deepCopy());
        assertThatThrownBy(() -> definitions.apply(definition, true)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DUPLICATE_NAME));
    }
    @Test void T_05_CP_06_bodyFilesResolveRelativeToDefinitionAndValidateBeforeAnyWrite(@TempDir Path temporary) throws Exception {
        var definition = codec.read(Path.of("seeds/club-minim.yaml")); definition.remove("accounts");
        var page = definition.putArray("pages").addObject().put("key", "RULES").put("active", true);
        page.putObject("title").put("en", "Title"); page.putObject("bodyFile").put("en", "body.md");
        Files.writeString(temporary.resolve("body.md"), "${PROVISIONAL_TEXT}\n\n# Rules");
        Path file = temporary.resolve("club.yaml"); Files.writeString(file, codec.write(definition));
        assertThat(codec.read(file).at("/pages/0/body/en").asText()).startsWith(ProvisionalText.MARKER);
        definitions.apply(file, false);
        Files.writeString(temporary.resolve("body.md"), "<script>bad</script>");
        long count = mongo.count(new Query(), "domain_events");
        assertThatThrownBy(() -> definitions.apply(file, false)).isInstanceOf(ApiException.class);
        assertThat(mongo.count(new Query(), "domain_events")).isEqualTo(count);
        Files.writeString(temporary.resolve("body.md"), "x".repeat(80001)); assertThatThrownBy(() -> codec.read(file)).isInstanceOf(ApiException.class);
        Files.delete(temporary.resolve("body.md")); assertThatThrownBy(() -> codec.read(file)).isInstanceOf(ApiException.class);
        page.withObject("bodyFile").put("en", temporary.resolve("body.md").toString()); Files.writeString(file, codec.write(definition));
        assertThatThrownBy(() -> codec.read(file)).isInstanceOf(ApiException.class);
        page.withObject("bodyFile").put("en", "body.md"); page.putObject("body").put("en", "Conflicting body"); Files.writeString(file, codec.write(definition));
        assertThatThrownBy(() -> codec.read(file)).isInstanceOf(ApiException.class);
    }
}
