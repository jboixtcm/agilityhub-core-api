package com.agilityhub.core.clubs.catalogs.api;

import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.catalogs.domain.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CatalogsIT extends AbstractIntegrationTest {
    static final String CLUB = "catalog-a", OTHER = "catalog-b", HOST = "catalog-a.example.test";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired CatalogService catalogs;
    @Autowired UsageCounter usage;
    @Autowired MongoTransactionManager transactions;

    @BeforeEach void seed() {
        TenantContext.clear();
        for (Class<?> type : List.of(Club.class, Parameter.class, Level.class, Ring.class, FaqEntry.class, AuditEntry.class, DomainEventRecord.class)) { mongo.remove(new Query(), type); }
        for (String collection : List.of("dogs", "class_sessions", "template_classes", "training_bookings", "training_slots", "ring_blocks", "placements", "catalog_write_locks")) { mongo.remove(new Query(), collection); }
        clubs.save(PlatformFixtures.club(CLUB, HOST));
        clubs.save(PlatformFixtures.club(OTHER, "catalog-b.example.test"));
        configs.invalidate(CLUB); configs.invalidate(OTHER); hosts.invalidate();
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject("catalog-admin")
                .claim("name", "Example Catalog Admin").claim("clubId", club)).authorities(() -> "ROLE_" + role)));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "ADMIN"); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(body));
    }
    JsonNode json(ResultActions action, int status) throws Exception {
        return mapper.readTree(action.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    Map<String, Object> input(String catalog, String suffix) {
        return switch (catalog) {
            case "levels" -> Map.of("code", "L" + suffix, "name", Map.of("ca", "Level " + suffix, "es", "Nivel " + suffix));
            case "rings" -> Map.of("name", "Ring " + suffix, "shortName", "R" + suffix);
            default -> Map.of("category", Map.of("ca", "Category " + suffix), "question", Map.of("ca", "Question " + suffix), "answer", Map.of("ca", "Answer " + suffix));
        };
    }
    JsonNode create(String catalog, String suffix) throws Exception { return json(admin(body(post("/api/v1/" + catalog), input(catalog, suffix))), 201); }
    String orderKey(String catalog) { return switch(catalog) { case "levels" -> "levelIds"; case "rings" -> "ringIds"; default -> "faqEntryIds"; }; }
    JsonNode update(String catalog, String id, Object patch) throws Exception { return json(admin(body(patch("/api/v1/" + catalog + "/" + id), patch)), 200); }
    void reference(String collection, String club, String field, Object id, String status, int futureHours) {
        mongo.insert(new Document("_id", UUID.randomUUID().toString()).append("clubId", club).append(field, id).append("status", status)
                .append("startsAt", Date.from(clock.instant().plus(Duration.ofHours(futureHours)))), collection);
    }
    void modules(Set<Module> modules) {
        var tree = mapper.valueToTree(clubs.findById(CLUB).orElseThrow());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
    }

    @ParameterizedTest @ValueSource(strings = {"levels", "rings", "faq-entries"})
    @AuditCovers(AuditAction.CATALOG_CHANGED)
    void T_05_06_T_05_19_T_05_20_catalogCrudOrderVersionAuditAndOutbox(String catalog) throws Exception {
        var first = create(catalog, "1"); var second = create(catalog, "2");
        String id = first.path("id").asText(), secondId = second.path("id").asText();
        assertThat(first.path("order").asInt()).isZero(); assertThat(second.path("order").asInt()).isEqualTo(10);
        assertThat(first.path("version").asLong()).isZero();
        assertThat(first.path("lastChange").path("action").asText()).isEqualTo("CATALOG_CHANGED");
        assertThat(first.path("lastChange").path("actorName").asText()).isEqualTo("Example Catalog Admin");
        String path = "/api/v1/" + catalog;
        admin(body(put(path + "/order"), Map.of(orderKey(catalog), List.of(id)))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ORDER_INCOMPLETE"));
        admin(body(put(path + "/order"), Map.of(orderKey(catalog), List.of(id, id)))).andExpect(status().isUnprocessableEntity());
        admin(body(put(path + "/order"), Map.of(orderKey(catalog), List.of(id, "foreign")))).andExpect(status().isUnprocessableEntity());
        var ordered = json(admin(body(put(path + "/order"), Map.of(orderKey(catalog), List.of(secondId, id)))), 200);
        assertThat(ordered.path("items").findValuesAsText("id")).containsExactly(secondId, id);
        assertThat(ordered.path("items").get(0).path("order").asInt()).isZero();
        assertThat(ordered.path("items").get(1).path("order").asInt()).isEqualTo(10);
        var inactive = update(catalog, id, Map.of("active", false, "version", 1));
        assertThat(inactive.path("version").asLong()).isEqualTo(2);
        assertThat(json(admin(get(path)), 200).path("items").findValuesAsText("id")).containsExactly(secondId);
        assertThat(json(admin(get(path).param("includeInactive", "true")), 200).path("items")).hasSize(2);
        admin(body(patch(path + "/" + id), Map.of("active", true, "version", 1))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        update(catalog, id, Map.of("active", true, "version", 2));
        if (!catalog.equals("faq-entries")) { admin(get(path + "/" + id)).andExpect(status().isOk()); }
        var item = mongo.findOne(Query.query(Criteria.where("_id").is(id)), Document.class, catalog.replace('-', '_'));
        assertThat(item.get("clubId")).isEqualTo(CLUB);
        assertThat(item.get("createdByAccountId")).isEqualTo("catalog-admin");
        assertThat(item.get("updatedByAccountId")).isEqualTo("catalog-admin");
        assertThat(item.getDate("createdAt")).isEqualTo(Date.from(clock.instant()));
        assertThat(item.getDate("updatedAt")).isEqualTo(Date.from(clock.instant()));
        assertThat(item).doesNotContainKey("agilityhubLevel");
        admin(delete(path + "/" + id)).andExpect(status().isNoContent());
        var events = mongo.findAll(DomainEventRecord.class);
        assertThat(events).hasSize(7);
        assertThat(events.stream().map(event -> event.payload().get("action"))).contains("CREATED", "REORDERED", "DEACTIVATED", "REACTIVATED", "DELETED");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.clubId()).isEqualTo(CLUB); assertThat(event.payload()).containsKeys("id", "diff", "action");
        });
        var audits = mongo.findAll(AuditEntry.class);
        assertThat(audits).hasSize(6).allSatisfy(entry -> assertThat(entry.action()).isEqualTo(AuditAction.CATALOG_CHANGED));
        assertThat(audits.getLast().changes()).anySatisfy(change -> { assertThat(change.before()).isNotNull(); assertThat(change.after()).isNull(); });
        // Repeating an already-complete order does not change versions or emit more domain events.
        admin(body(put(path + "/order"), Map.of(orderKey(catalog), List.of(secondId)))).andExpect(status().isOk());
        assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(7);
    }

    @Test void T_05_01_levelsRemainEditableWhenDisabledAndDefaultsAreConfigured() throws Exception {
        mongo.insert(new Parameter("levels-off", CLUB, "levels.enabled", false, "bool", "club", null, List.of(), 1L, clock.instant()));
        mongo.insert(new Parameter("capacity", CLUB, "classes.defaultCapacity", 8, "int", "club", null, List.of(), 1L, clock.instant()));
        configs.invalidate(CLUB);
        assertThat(create("levels", "1").path("capacity").asInt()).isEqualTo(8);
        assertThat(create("levels", "2").path("color").asText()).isNotEmpty();
        var node = json(admin(body(post("/api/v1/levels"), Map.of("code", "D", "name", Map.of("ca", "Advanced"), "capacity", 4, "color", "#112233", "grantsFreeTraining", true, "order", 90))), 201);
        assertThat(node.path("capacity").asInt()).isEqualTo(4); assertThat(node.path("grantsFreeTraining").asBoolean()).isTrue();
    }

    @Test void T_05_08_localizedValidationFallbackAndReaderProjection() throws Exception {
        String id = create("levels", "1").path("id").asText();
        admin(get("/api/v1/levels/" + id).header("Accept-Language", "es")).andExpect(jsonPath("$.name").value("Nivel 1"));
        update("levels", id, Map.of("name", Map.of("ca", "Only Catalan"), "version", 0));
        admin(get("/api/v1/levels/" + id).header("Accept-Language", "en")).andExpect(jsonPath("$.name").value("Only Catalan"));
        for (Map<String, String> invalid : List.of(Map.of("es", "Missing default"), Map.of("ca", "Default", "fr", "Disabled"), Map.of("ca", " "), Map.of("ca", "x".repeat(41)))) {
            admin(body(patch("/api/v1/levels/" + id), Map.of("name", invalid, "version", 1))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        admin(body(patch("/api/v1/levels/" + id), Map.of("name", Map.of("ca", "Name", "fr", "Disabled"), "version", 1)))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("LOCALE_NOT_ENABLED"));
        create("rings", "1"); create("faq-entries", "1");
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            for (String catalog : List.of("levels", "rings", "faq-entries")) {
                var item = json(call(get("/api/v1/" + catalog), CLUB, role), 200).path("items").get(0);
                assertThat(item.has("usage")).isFalse(); assertThat(item.has("lastChange")).isFalse();
                assertThat(item.fieldNames()).toIterable().noneMatch(name -> name.endsWith("I18n"));
                call(get("/api/v1/" + catalog).param("includeInactive", "true"), CLUB, role).andExpect(status().isForbidden());
            }
        }
    }

    @Test void T_05_10_levelsWithReferencesCanDeactivateButCannotBeDeleted() throws Exception {
        String id = create("levels", "1").path("id").asText();
        reference("dogs", CLUB, "levelId", id, "ACTIVE", 0);
        reference("template_classes", CLUB, "levelIds", List.of(id), "ACTIVE", 0);
        admin(delete("/api/v1/levels/" + id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEVEL_IN_USE")).andExpect(jsonPath("$.details.activeDogs").value(1));
        var inactive = update("levels", id, Map.of("active", false, "version", 0));
        assertThat(inactive.at("/warnings/activeDogs").asInt()).isEqualTo(1); assertThat(inactive.at("/warnings/templateClasses").asInt()).isEqualTo(1);
        mongo.remove(new Query(), "dogs"); mongo.remove(new Query(), "template_classes");
        reference("dogs", CLUB, "levelId", id, "INACTIVE", 0);
        admin(delete("/api/v1/levels/" + id)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.activeDogs").value(0));
        mongo.remove(new Query(), "dogs");
        reference("dogs", OTHER, "levelId", id, "ACTIVE", 0);
        admin(delete("/api/v1/levels/" + id)).andExpect(status().isNoContent());
    }

    @Test void T_05_11_caseInsensitiveUniquenessIncludesInactiveCodesAndRings() throws Exception {
        String id = create("rings", "1").path("id").asText();
        for (var values : List.of(Map.of("name", "Different", "shortName", "r1"), Map.of("name", "rInG 1", "shortName", "ZZ"))) {
            admin(body(post("/api/v1/rings"), values)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));
        }
        update("rings", id, Map.of("active", false, "version", 0));
        admin(body(post("/api/v1/rings"), input("rings", "1"))).andExpect(status().isConflict()).andExpect(jsonPath("$.details.field").value("name"));
        String level = create("levels", "1").path("id").asText();
        admin(body(post("/api/v1/levels"), Map.of("code", "l1", "name", Map.of("ca", "Different"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.field").value("code"));
        admin(body(post("/api/v1/levels"), Map.of("code", "LX", "name", Map.of("ca", "level 1"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.field").value("name"));
        update("levels", level, Map.of("active", false, "version", 0));
        var replacement = json(admin(body(post("/api/v1/levels"), Map.of("code", "LX", "name", Map.of("ca", "level 1")))), 201);
        admin(body(patch("/api/v1/levels/" + level), Map.of("active", true, "version", 1))).andExpect(status().isConflict());
        update("levels", replacement.path("id").asText(), Map.of("name", Map.of("ca", "Replacement"), "version", 0));
        update("levels", level, Map.of("active", true, "version", 1));
    }

    @Test void T_05_05_T_05_12_ringGuardsEffectiveCapacityAndGeometryPreservation() throws Exception {
        var ring = json(admin(body(post("/api/v1/rings"), Map.of("name", "Ring", "shortName", "RR", "allowsFreeTraining", true))), 201);
        String id = ring.path("id").asText();
        assertThat(ring.path("effectiveTrainingCapacity").asInt()).isEqualTo(1);
        reference("class_sessions", CLUB, "ringId", id, "SCHEDULED", 24);
        admin(body(patch("/api/v1/rings/" + id), Map.of("active", false, "version", 0))).andExpect(status().isConflict()).andExpect(jsonPath("$.details.futureClassSessions").value(1));
        mongo.remove(new Query(), "class_sessions");
        reference("training_bookings", CLUB, "ringId", id, "CONFIRMED", 24);
        admin(body(patch("/api/v1/rings/" + id), Map.of("allowsFreeTraining", false, "version", 0))).andExpect(status().isConflict()).andExpect(jsonPath("$.details.futureTrainingBookings").value(1));
        admin(body(patch("/api/v1/rings/" + id), Map.of("active", false, "version", 0))).andExpect(status().isConflict());
        mongo.remove(new Query(), "training_bookings");
        reference("class_sessions", CLUB, "ringId", id, "CANCELLED", 24);
        reference("class_sessions", CLUB, "ringId", id, "SCHEDULED", -24);
        reference("class_sessions", OTHER, "ringId", id, "SCHEDULED", 24);
        reference("template_classes", CLUB, "ringId", id, "ACTIVE", 24);
        reference("ring_blocks", CLUB, "ringId", id, "ACTIVE", 24);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("geometry", Map.of("width", 20)).set("activeSetupId", "setup"), "rings");
        var updated = update("rings", id, Map.of("allowsFreeTraining", false, "trainingCapacity", 2, "version", 0, "geometry", Map.of("width", 99), "activeSetupId", "other"));
        assertThat(updated.path("effectiveTrainingCapacity").asInt()).isEqualTo(2);
        assertThat(update("rings", id, Map.of("color", "#112233", "version", 1)).path("effectiveTrainingCapacity").asInt()).isEqualTo(2);
        var reset = json(admin(patch("/api/v1/rings/" + id).contentType("application/json").content("{\"trainingCapacity\":null,\"version\":2}")), 200);
        assertThat(reset.path("effectiveTrainingCapacity").asInt()).isEqualTo(1);
        update("rings", id, Map.of("active", false, "version", 3));
        var stored = mongo.findOne(Query.query(Criteria.where("_id").is(id)), Document.class, "rings");
        assertThat(stored.get("geometry", Document.class).getInteger("width")).isEqualTo(20); assertThat(stored.getString("activeSetupId")).isEqualTo("setup");
        admin(delete("/api/v1/rings/" + id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RING_IN_USE"));
        assertThat(mongo.findAll(DomainEventRecord.class)).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("RingChanged"); assertThat(event.payload().get("action")).isEqualTo("UPDATED");
            assertThat(((Map<?, ?>) event.payload().get("diff")).containsKey("allowsFreeTraining")).isTrue();
        });
    }

    @Test void T_05_09_faqGroupsUseResolvedCategoryAndMinimumOrderAndCountSuggestions() throws Exception {
        for (var row : List.of(new Object[]{"Group B", "First", 10, true}, new Object[]{"Group A", "Second", 20, true},
                new Object[]{"Group B", "Third", 30, true}, new Object[]{"Hidden", "Hidden", 0, false})) {
            admin(body(post("/api/v1/faq-entries"), Map.of("category", Map.of("ca", row[0]), "question", Map.of("ca", row[1]),
                    "answer", Map.of("ca", "Plain text\nNext line"), "order", row[2], "active", row[3]))).andExpect(status().isCreated());
        }
        var faqs = json(admin(get("/api/v1/faq-entries")), 200);
        assertThat(faqs.path("items").findValuesAsText("question")).containsExactly("First", "Third", "Second");
        var categories = json(admin(get("/api/v1/faq-entries/filter-values").param("field", "category").param("q", "group")), 200);
        assertThat(categories.path("values").findValuesAsText("value")).containsExactly("Group B", "Group A");
        assertThat(categories.at("/values/0/count").asInt()).isEqualTo(2);
        for (String filter : List.of("category:eq:Group B", "category:ne:Group A", "category:contains:b", "category:startsWith:Group B", "category:in:Group B,Other", "category:nin:Group A,Hidden")) {
            assertThat(json(admin(get("/api/v1/faq-entries/filter-values").param("field", "category").param("q", "Group").param("filter", filter)), 200)
                    .path("values").findValuesAsText("value")).containsExactly("Group B");
        }
        for (String filter : List.of("bad", "question:eq:First", "category:lt:x")) {
            admin(get("/api/v1/faq-entries/filter-values").param("field", "category").param("filter", filter)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        }
        admin(get("/api/v1/faq-entries/filter-values").param("field", "question")).andExpect(status().isBadRequest());
        assertThat(json(admin(get("/api/v1/faq-entries/filter-values").param("field", "category")), 200).path("values")).hasSize(3);
    }

    @ParameterizedTest @ValueSource(strings = {"levels", "rings", "faq-entries"})
    void T_05_19_everyEndpointEnforcesRolesTenantAndInactiveAccess(String catalog) throws Exception {
        String id = create(catalog, "1").path("id").asText();
        String path = "/api/v1/" + catalog;
        List<MockHttpServletRequestBuilder> adminRequests = new ArrayList<>(List.of(body(post(path), input(catalog, "2")),
                body(patch(path + "/" + id), Map.of("active", false, "version", 0)), delete(path + "/" + id),
                body(put(path + "/order"), Map.of(orderKey(catalog), List.of(id)))));
        if (!catalog.equals("faq-entries")) { adminRequests.add(get(path + "/" + id)); }
        else { adminRequests.add(get(path + "/filter-values").param("field", "category")); }
        for (var request : adminRequests) {
            mvc.perform(request.header("Host", HOST)).andExpect(status().isUnauthorized());
            for (String role : List.of("MEMBER", "INSTRUCTOR", "AGILITYHUB_ADMIN")) { call(request, CLUB, role).andExpect(status().isForbidden()); }
            mvc.perform(request.header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", OTHER)).authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
            // Missing persisted impersonation grants are rejected by the common filter before reaching the admin guard.
            mvc.perform(request.header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB).claim("imp", true)).authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isUnauthorized());
        }
        call(get(path), CLUB, "AGILITYHUB_ADMIN").andExpect(status().isForbidden());
        mvc.perform(get(path).header("Host", HOST)).andExpect(status().isUnauthorized());
        assertThat(json(call(get(path), OTHER, "ADMIN"), 200).path("items")).isEmpty();
        call(body(patch(path + "/" + id), Map.of("version", 0)), OTHER, "ADMIN").andExpect(status().isNotFound());
        call(delete(path + "/" + id), OTHER, "ADMIN").andExpect(status().isNotFound());
        if (!catalog.equals("faq-entries")) { call(get(path + "/" + id), OTHER, "ADMIN").andExpect(status().isNotFound()); }
        call(body(put(path + "/order"), Map.of(orderKey(catalog), List.of(id))), OTHER, "ADMIN").andExpect(status().isUnprocessableEntity());
        call(body(post(path), input(catalog, "1")), OTHER, "ADMIN").andExpect(status().isCreated());
        assertThat(json(admin(get(path)), 200).path("items")).hasSize(1);
        if (catalog.equals("faq-entries")) {
            modules(Set.of());
            admin(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
            for (var request : adminRequests) { admin(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED")); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"levels", "rings", "faq-entries"})
    void T_05_08_invalidCreatePatchAndOrderBodiesWriteNothing(String catalog) throws Exception {
        String path = "/api/v1/" + catalog;
        admin(body(post(path), Map.of())).andExpect(status().isBadRequest());
        String id = create(catalog, "1").path("id").asText();
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("active", false), Map.<String, Object>of("version", 0, "order", -1))) {
            admin(body(patch(path + "/" + id), invalid)).andExpect(status().isBadRequest());
        }
        admin(body(put(path + "/order"), Map.of())).andExpect(status().isBadRequest());
        if (catalog.equals("rings")) {
            for (Object capacity : List.of(0, 21, 1.5, "2", false)) {
                admin(body(patch(path + "/" + id), Map.of("trainingCapacity", capacity, "version", 0))).andExpect(status().isBadRequest());
            }
            admin(body(patch(path + "/" + id), Map.of("name", " ", "version", 0))).andExpect(status().isBadRequest());
        } else if (catalog.equals("faq-entries")) {
            for (String field : List.of("category", "question", "answer")) {
                admin(body(patch(path + "/" + id), Map.of(field, Map.of("es", "Missing default"), "version", 0))).andExpect(status().isBadRequest());
            }
        } else {
            for (var invalid : List.of(Map.of("capacity", 0, "version", 0), Map.of("code", "!", "version", 0), Map.of("color", "red", "version", 0))) {
                admin(body(patch(path + "/" + id), invalid)).andExpect(status().isBadRequest());
            }
        }
        assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(1); assertThat(mongo.findAll(AuditEntry.class)).hasSize(1);
    }

    @Test void T_05_19_referenceProjectionsCountHistoricalRowsAndScopeEveryCollection() throws Exception {
        try (var scope = TenantContext.open(CLUB)) {
            for (String collection : List.of("class_sessions", "template_classes", "training_slots", "training_bookings", "ring_blocks", "placements")) {
                reference(collection, OTHER, "ringId", "ring", "CANCELLED", -24);
                assertThat(usage.hasReferences(CatalogKind.RING, "ring")).isFalse();
                reference(collection, CLUB, "ringId", "ring", "CANCELLED", -24);
                assertThat(usage.hasReferences(CatalogKind.RING, "ring")).isTrue();
                mongo.remove(new Query(), collection);
            }
            for (String collection : List.of("class_sessions", "template_classes")) {
                reference(collection, CLUB, "levelIds", List.of("level"), "CANCELLED", -24);
                assertThat(usage.hasReferences(CatalogKind.LEVEL, "level")).isTrue(); mongo.remove(new Query(), collection);
            }
            assertThat(usage.hasReferences(CatalogKind.FAQ, "faq")).isFalse();
        }
    }

    @Test void T_05_20_concurrentEditsHaveOneWinnerAndAtomicRollbackLeavesNoAuditOrOutbox() throws Exception {
        var created = create("levels", "1"); String id = created.path("id").asText();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int capacity : List.of(6, 7)) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return admin(body(patch("/api/v1/levels/" + id), Map.of("capacity", capacity, "version", 0))).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(2); assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(2);
        try (var scope = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                catalogs.create(CatalogKind.LEVEL, input("levels", "9"));
                throw new IllegalStateException("Rollback fixture");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(mongo.findAll(Level.class)).hasSize(1); assertThat(mongo.findAll(AuditEntry.class)).hasSize(2); assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(2);
    }
    @Test void T_05_19_validImpersonationCanReadButEveryAdminCatalogEndpointRejectsIt() throws Exception {
        var actorId = "catalog-actor"; var targetId = "catalog-target"; var memberId = "catalog-member"; var grantId = "catalog-grant";
        for (String accountId : List.of(actorId, targetId)) {
            mongo.save(new com.agilityhub.core.identity.persistence.Account(accountId, accountId + "@example.test", "Example Person", "ca", null,
                    Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,
                    new com.agilityhub.core.identity.persistence.Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
            var role = accountId.equals(actorId) ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(accountId + "-membership", accountId, CLUB,
                    accountId.equals(targetId) ? memberId : "catalog-admin-member", Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
        }
        mongo.save(new Document("_id", memberId).append("clubId", CLUB).append("accountId", targetId).append("status", "ACTIVE"), "members");
        mongo.save(new com.agilityhub.core.identity.persistence.ImpersonationGrant(grantId, CLUB, actorId, memberId, targetId,
                "Example support request", clock.instant().plusSeconds(300), null));
        for (String catalog : List.of("levels", "rings", "faq-entries")) {
            String id = create(catalog, "1").path("id").asText(); String path = "/api/v1/" + catalog;
            List<MockHttpServletRequestBuilder> requests = new ArrayList<>(List.of(body(post(path), input(catalog, "2")),
                    body(patch(path + "/" + id), Map.of("active", false, "version", 0)), delete(path + "/" + id),
                    body(put(path + "/order"), Map.of(orderKey(catalog), List.of(id))), get(path).param("includeInactive", "true")));
            requests.add(catalog.equals("faq-entries") ? get(path + "/filter-values").param("field", "category") : get(path + "/" + id));
            for (var request : requests) {
                mvc.perform(request.header("Host", HOST).with(jwt().jwt(j -> j.subject(targetId).claim("clubId", CLUB).claim("imp", true)
                        .claim("jti", grantId).claim("actorAccountId", actorId).claim("impersonatedMemberId", memberId))
                        .authorities(() -> "ROLE_MEMBER"))).andExpect(status().isForbidden());
            }
            mvc.perform(get(path).header("Host", HOST).with(jwt().jwt(j -> j.subject(targetId).claim("clubId", CLUB).claim("imp", true)
                    .claim("jti", grantId).claim("actorAccountId", actorId).claim("impersonatedMemberId", memberId))
                    .authorities(() -> "ROLE_MEMBER"))).andExpect(status().isOk());
        }
    }

    @Test void T_05_09_categoryFiltersValidateEvenForEmptyCatalogsAndEmptyMatches() throws Exception {
        admin(get("/api/v1/faq-entries/filter-values").param("field", "category").param("filter", "category:unknown:value"))
                .andExpect(status().isBadRequest());
        create("faq-entries", "1");
        admin(get("/api/v1/faq-entries/filter-values").param("field", "category").param("q", "absent")
                .param("filter", "category:eq:absent", "other:eq:bad")).andExpect(status().isBadRequest());
    }

}
