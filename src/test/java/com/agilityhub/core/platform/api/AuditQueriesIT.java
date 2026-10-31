package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.context.SpringBootTest(properties = "shared.scheduling.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuditQueriesIT extends AbstractIntegrationTest {
    static final String CLUB = "audit-read-a", OTHER = "audit-read-b", MEMBER = "audit-member-a";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired AuditLists provider;
    @Autowired com.agilityhub.core.shared.persistence.MongoListRepository listRepository;
    @Autowired com.agilityhub.core.clubs.common.application.ExportWorker worker;
    String requesterLocale = "en";
    @BeforeEach void seed() {
        clean();
        for (String club : List.of(CLUB, OTHER)) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(PlatformFixtures.club(club, club + ".example.test"));
            tree.set("modules", mapper.valueToTree(Module.values())); clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(club);
        }
        hosts.invalidate();
        mongo.insert(new Document("_id", MEMBER).append("clubId", CLUB).append("firstName", "Fictional").append("lastName1", "Member"), "members");
        mongo.insert(entry("old", CLUB, "MEMBER_UPDATED", "2026-08-01T10:00:00Z").append("memberId", MEMBER), "audit_entries");
        mongo.insert(entry("direct", CLUB, "MEMBER_PAYMENT_METHOD_CHANGED", "2026-08-02T10:00:00Z").append("memberId", MEMBER), "audit_entries");
        mongo.insert(entry("impersonated", CLUB, "DOG_UPDATED", "2026-08-03T10:00:00Z").append("impersonatedMemberId", MEMBER).append("actorRole", "MEMBER"), "audit_entries");
        mongo.insert(entry("unrelated", CLUB, "CATALOG_CHANGED", "2026-08-04T10:00:00Z"), "audit_entries");
        mongo.insert(entry("foreign", OTHER, "MEMBER_UPDATED", "2026-08-05T10:00:00Z").append("memberId", MEMBER).append("impersonatedMemberId", MEMBER), "audit_entries");
    }
    @AfterEach void clean() {
        TenantContext.clear();
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String collection : List.of("members", "audit_entries", "levels", "parameters", "export_jobs", "export_write_locks", "domain_events")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
    }
    Document entry(String id, String club, String action, String at) {
        return new Document("_id", "audit-" + id).append("clubId", club).append("at", Date.from(Instant.parse(at)))
                .append("action", action).append("actorAccountId", "audit-admin").append("actorName", "Example Admin").append("actorRole", "ADMIN")
                .append("entityType", "Member").append("entityId", MEMBER).append("entityLabel", "Fictional member")
                .append("reason", "Example correction").append("traceId", "fictional-trace").append("ip", "192.0.2.1").append("userAgent", "Audit fixture")
                .append("changes", List.of(new Document("path", "paymentMethod.iban").append("before", null).append("after", "ES0000000000000000002231")))
                .append("details", new Document("nested", List.of(new Document("passwordHash", "fictional-password-value").append("accessToken", "fictional-access-value")))
                        .append("idDocument", new Document("number", "AB123456CD")).append("iban", "ES0000000000000000002231").append("reason", "Visible detail"));
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject("audit-admin").claim("name", "Example Admin")
                .claim("clubId", club).claim("locale", requesterLocale)).authorities(() -> "ROLE_" + role)));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "ADMIN"); }
    JsonNode json(ResultActions result) throws Exception { return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    @Test void T_14_13_memberHistoryIncludesDirectAndImpersonatedEntriesNewestFirst() throws Exception {
        var page = json(admin(get("/api/v1/members/" + MEMBER + "/audit-entries")));
        assertThat(page.path("items").findValuesAsText("id")).containsExactly("audit-impersonated", "audit-direct", "audit-old");
        assertThat(page.path("items").get(0).path("impersonatedName").asText()).isEqualTo("Fictional Member");
        assertThat(page.path("items").get(0).path("origin").asText()).isEqualTo("BACKOFFICE");
        var filtered = json(admin(get("/api/v1/members/" + MEMBER + "/audit-entries").param("filter", "action:eq:MEMBER_PAYMENT_METHOD_CHANGED")));
        assertThat(filtered.path("totalItems").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("id").asText()).isEqualTo("audit-direct");
        assertThat(json(admin(get("/api/v1/audit-entries"))).path("totalItems").asInt()).isEqualTo(4);
    }
    @Test void T_14_13_universalFiltersSearchFacetsSortPaginationAndSparseFields() throws Exception {
        String path = "/api/v1/audit-entries";
        var page = json(admin(get(path).param("filter", "at:between:2026-08-02T00:00:00Z,2026-08-03T23:59:59Z", "actorAccountId:eq:audit-admin", "entityType:eq:Member")
                .param("q", "example").param("sort", "at,asc").param("fields", "at,action")));
        assertThat(page.path("items").findValuesAsText("id")).containsExactly("audit-direct", "audit-impersonated");
        assertThat(page.path("items").get(0).size()).isEqualTo(3);
        assertThat(json(admin(get(path).param("q", "Fictional member"))).path("totalItems").asInt()).isEqualTo(4);
        assertThat(json(admin(get(path).param("q", "correction"))).path("totalItems").asInt()).isEqualTo(4);
        assertThat(json(admin(get(path).param("page", "1").param("size", "20"))).path("items")).isEmpty();
        var facets = json(admin(get(path + "/filter-values").param("field", "action").param("filter", "action:eq:DOG_UPDATED", "origin:eq:BACKOFFICE")));
        assertThat(facets.path("values")).hasSize(4);
        for (String invalid : List.of("foo:eq:1", "at:between:no,no", "at:eq:2026-08-01T00:00:00Z")) {
            admin(get(path).param("filter", invalid)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        }
        admin(get(path + "/filter-values").param("field", "foo")).andExpect(status().isBadRequest());
        admin(get(path).param("sort", "reason,asc")).andExpect(status().isBadRequest());
        admin(get(path).param("fields", "ip")).andExpect(status().isBadRequest());
        admin(get("/api/v1/members/" + MEMBER + "/audit-entries").param("filter", "foo:eq:1")).andExpect(status().isBadRequest());
    }
    @Test void T_14_13_detailsAndExportsMaskNestedSensitiveData() throws Exception {
        var detail = json(admin(get("/api/v1/audit-entries/audit-direct")));
        assertThat(detail.path("changes").get(0).has("before")).isTrue();
        assertThat(detail.path("changes").get(0).path("before").isNull()).isTrue();
        assertThat(detail.path("eventIds").isArray()).isTrue();
        assertThat(detail.toString()).contains("2231", "Visible detail", "[ocult]", "fictional-trace").doesNotContain("ES0000", "AB123456CD", "fictional-password-value", "fictional-access-value");
        for (String locale : List.of("ca", "es", "en")) {
            requesterLocale = locale;
            byte[] bytes = admin(get("/api/v1/audit-entries/export").header("Accept-Language", locale).param("format", "xlsx")
                    .param("filter", "action:eq:MEMBER_PAYMENT_METHOD_CHANGED").param("columns", "action,changes,details"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var sheet = book.getSheetAt(0); assertThat(sheet.getLastRowNum()).isEqualTo(1);
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(Map.of("ca", "Acció", "es", "Acción", "en", "Action").get(locale));
                String cells = sheet.getRow(1).getCell(1).getStringCellValue() + sheet.getRow(1).getCell(2).getStringCellValue();
                assertThat(cells).contains("2231", "[ocult]", "Visible detail").doesNotContain("ES0000", "AB123456CD", "fictional-password-value", "fictional-access-value");
            }
        }
        byte[] pdf = admin(post("/api/v1/audit-entries/export").param("format", "pdf").param("filter", "action:eq:DOG_UPDATED")
                .param("columns", "action,details")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("DOG_UPDATED", "Visible detail").doesNotContain("ES0000", "fictional-password-value");
        }
    }
    @Test void T_14_13_legacyMetadataFallsBackWithoutCrossTenantNameJoins() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("audit-old")), new Update().unset("entityLabel").unset("changes").set("actorRole", "SYSTEM"), "audit_entries");
        var row = json(admin(get("/api/v1/audit-entries/audit-old")));
            assertThat(row.path("origin").asText()).isEqualTo("SYSTEM");
            assertThat(row.path("entityLabel").asText()).contains("Member", MEMBER);
            assertThat(row.path("changes")).isEmpty();
        for (String role : List.of("MEMBER", "WEBHOOK")) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is("audit-old")), new Update().set("actorRole", role), "audit_entries");
            assertThat(json(admin(get("/api/v1/audit-entries/audit-old"))).path("origin").asText()).isEqualTo(role.equals("MEMBER") ? "APP" : "WEBHOOK");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("audit-old")), new Update().set("origin", "APP").set("actorRole", "SYSTEM")
                .set("eventIds", List.of("fictional-event")), "audit_entries");
        var stored = json(admin(get("/api/v1/audit-entries/audit-old")));
        assertThat(stored.path("origin").asText()).isEqualTo("APP"); assertThat(stored.path("eventIds").get(0).asText()).isEqualTo("fictional-event");
        var foreign = json(call(get("/api/v1/audit-entries/audit-foreign"), OTHER, "ADMIN"));
        assertThat(foreign.has("impersonatedName")).isFalse();
    }
    @Test void T_14_13_missingImpersonationUsesTheIdIndexWithoutScanningTheCensus() {
        var members = new ArrayList<Document>();
        var entries = new ArrayList<Document>();
        for (int i = 0; i < 200; i++) {
            members.add(new Document("_id", "audit-perf-member-" + i).append("clubId", CLUB)
                    .append("firstName", "Fictional").append("lastName1", "Member " + i));
            entries.add(entry("perf-" + i, CLUB, "MEMBER_UPDATED", "2026-08-01T10:00:00Z"));
        }
        mongo.insert(members, "members");
        mongo.insert(entries, "audit_entries");
        var stages = new ArrayList<Document>();
        stages.add(new Document("$match", new Document("clubId", CLUB)));
        stages.addAll(new com.agilityhub.core.platform.persistence.audit.AuditListProjection(mongo).stages());
        var explain = mongo.executeCommand(new Document("explain", new Document("aggregate", "audit_entries")
                .append("pipeline", stages).append("cursor", new Document())).append("verbosity", "executionStats"));
        var lookup = explain.getList("stages", Document.class).stream().filter(stage -> stage.containsKey("$lookup")).findFirst().orElseThrow();
        assertThat(((Number) lookup.get("collectionScans")).longValue()).as("No census scan per audit entry without impersonation").isZero();
        assertThat(((Number) lookup.get("totalDocsExamined")).longValue()).as("Only the impersonated member needs to be read").isLessThanOrEqualTo(1);
        assertThat(lookup.getList("indexesUsed", String.class)).contains("_id_");
    }
    @Test void T_14_13_impersonationNameFallbackPreservesSnapshotsAndHandlesMissingMembers() throws Exception {
        for (String id : List.of("null-member", "removed-member", "snapshot")) {
            var row = entry(id, CLUB, "MEMBER_UPDATED", "2026-08-06T10:00:00Z");
            row.put("impersonatedMemberId", id.equals("null-member") ? null : id.equals("snapshot") ? MEMBER : "removed");
            if (id.equals("snapshot")) row.put("impersonatedName", "Historical Name");
            mongo.insert(row, "audit_entries");
        }
        assertThat(json(admin(get("/api/v1/audit-entries/audit-null-member"))).has("impersonatedName")).isFalse();
        assertThat(json(admin(get("/api/v1/audit-entries/audit-removed-member"))).has("impersonatedName")).isFalse();
        assertThat(json(admin(get("/api/v1/audit-entries/audit-snapshot"))).path("impersonatedName").asText()).isEqualTo("Historical Name");
        assertThat(json(admin(get("/api/v1/audit-entries/audit-impersonated"))).path("impersonatedName").asText()).isEqualTo("Fictional Member");
        assertThat(json(admin(get("/api/v1/audit-entries/audit-old"))).has("impersonatedName")).isFalse();
    }
    @Test void T_14_13_pageEnrichmentMatchesFullHistoryForFiltersSearchAndMemberScope() {
        var entries = new ArrayList<Document>();
        for (int i = 0; i < 80; i++) {
            var row = entry("paged-" + i, CLUB, "MEMBER_UPDATED", "2026-08-01T10:00:00Z");
            if (i % 2 == 0) row.put("impersonatedMemberId", MEMBER);
            if (i % 3 == 0) row.remove("entityLabel");
            if (i % 5 == 0) row.put("origin", "INSTRUCTOR");
            entries.add(row);
        }
        mongo.insert(entries, "audit_entries");
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fictional").header("alg", "none").subject("audit-admin").build();
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var previous = security.getAuthentication();
        security.setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, List.of(() -> "ROLE_ADMIN")));
        try (var tenant = TenantContext.open(CLUB)) {
            for (var data : List.of(provider.dataset(false, null), provider.dataset(false, MEMBER))) {
                for (String scenario : List.of("", "q=Member ·", "q=correction", "q=no-match", "filter=origin:eq:BACKOFFICE", "filter=origin:eq:INSTRUCTOR",
                        "filter=action:eq:MEMBER_UPDATED", "filter=impersonatedMemberId:eq:" + MEMBER,
                        "filter=at:between:2026-08-02T00:00:00Z,2026-08-04T23:59:59Z", "sort=at,asc&page=1", "page=10", "fields=at,action")) {
                    var params = new org.springframework.util.LinkedMultiValueMap<String, String>(); params.add("size", "20");
                    if (!scenario.isEmpty()) for (String part : scenario.split("&")) { var pair = part.split("=", 2); params.add(pair[0], pair[1]); }
                    var query = com.agilityhub.core.shared.application.lists.ListQuery.parse(data.definition(), params);
                    assertThat(listRepository.page(data, query)).as(scenario)
                            .isEqualTo(listRepository.page(data.withScope(ignored -> new Document()), query));
                }
            }
        } finally { security.setAuthentication(previous); }
    }
    @Test void T_14_13_pageReadsOnlyTheDisplayedImpersonationNames() throws Exception {
        var entries = new ArrayList<Document>();
        for (int i = 0; i < 200; i++) entries.add(entry("paged-read-" + i, CLUB, "MEMBER_UPDATED", "2026-08-10T10:00:00Z").append("impersonatedMemberId", MEMBER));
        mongo.insert(entries, "audit_entries");
        var reads = com.agilityhub.core.support.MongoReads.during(mongo, List.of("audit_entries"), () -> {
            var result = json(admin(get("/api/v1/audit-entries").param("size", "20")));
            assertThat(result.path("totalItems").asInt()).isEqualTo(204);
            assertThat(result.path("items")).hasSize(20);
            assertThat(result.path("items").findValuesAsText("impersonatedName")).containsOnly("Fictional Member");
        });
        assertThat(reads).hasSize(1);
        assertThat(((Number) reads.getFirst().get("docsExamined")).longValue())
                .as("Count the history once and enrich only 20 displayed entries").isLessThanOrEqualTo(244);
    }
    /**
     * E5-T22 (found by ListFieldsContractIT on the Cànic demo). The writer stores no `null`, so a created value has no `before`
     * key and a cleared one no `after`. An instructor's action stores its event origin, INSTRUCTOR. The list and the detail
     * still send both keys (`null`), and the origin reads BACKOFFICE (S14 §3), also for the `origin` filter.
     */
    @Test void T_14_13_aChangeWithoutAStoredValueReadsNullAndAnInstructorsOriginReadsBackoffice() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("audit-direct")), new Update().set("origin", "INSTRUCTOR").set("actorRole", "INSTRUCTOR")
                .set("changes", List.of(new Document("path", "level").append("after", "B"), new Document("path", "handlerName").append("before", "Example handler"))), "audit_entries");
        var page = json(admin(get("/api/v1/audit-entries").param("filter", "origin:eq:BACKOFFICE")));
        assertThat(com.agilityhub.core.support.SnapshotSchemas.violations(page, "ListPageAuditEntryListItem")).isEmpty();
        JsonNode listed = null; for (var item : page.path("items")) { if (item.path("id").asText().equals("audit-direct")) { listed = item; } }
        assertThat(listed).as("audit-direct under origin BACKOFFICE").isNotNull();
        var detail = json(admin(get("/api/v1/audit-entries/audit-direct")));
        assertThat(com.agilityhub.core.support.SnapshotSchemas.violations(detail, "AuditEntry")).isEmpty();
        for (var row : List.of(listed, detail)) {
            assertThat(row.path("origin").asText()).isEqualTo("BACKOFFICE");
            assertThat(row.at("/changes/0/before").isNull()).as(row.path("changes").toString()).isTrue();
            assertThat(row.at("/changes/0/after").asText()).isEqualTo("B");
            assertThat(row.at("/changes/1/before").asText()).isEqualTo("Example handler");
            assertThat(row.at("/changes/1/after").isNull()).as(row.path("changes").toString()).isTrue();
        }
        assertThat(json(admin(get("/api/v1/audit-entries").param("filter", "origin:eq:INSTRUCTOR"))).path("items")).isEmpty();
    }
    @Test void T_14_22_everyAuditRouteEnforcesTenantRoleAndImpersonation() throws Exception {
        var requests = List.of(get("/api/v1/audit-entries"), get("/api/v1/audit-entries/audit-direct"),
                get("/api/v1/audit-entries/filter-values").param("field", "action"), get("/api/v1/members/" + MEMBER + "/audit-entries"),
                get("/api/v1/audit-entries/export").param("format", "xlsx"), post("/api/v1/audit-entries/export").param("format", "xlsx"));
        for (var request : requests) {
            for (String role : List.of("INSTRUCTOR", "MEMBER", "AGILITYHUB_ADMIN")) { call(request, CLUB, role).andExpect(status().isForbidden()); }
            mvc.perform(request.header("Host", CLUB + ".example.test").with(jwt().jwt(j -> j.subject("audit-admin").claim("clubId", CLUB).claim("imp", true))
                    .authorities(() -> "ROLE_ADMIN"))).andExpect(status().isUnauthorized());
        }
        call(get("/api/v1/audit-entries/audit-direct"), OTHER, "ADMIN").andExpect(status().isNotFound());
        call(get("/api/v1/members/" + MEMBER + "/audit-entries"), OTHER, "ADMIN").andExpect(status().isNotFound());
        admin(get("/api/v1/members/missing/audit-entries")).andExpect(status().isNotFound());
        assertThat(json(call(get("/api/v1/audit-entries"), OTHER, "ADMIN")).path("items").findValuesAsText("id")).containsExactly("audit-foreign");
        assertThat(json(call(get("/api/v1/audit-entries/filter-values").param("field", "action"), OTHER, "ADMIN")).path("values")).hasSize(1);
        for (var request : List.of(get("/api/v1/audit-entries/export"), post("/api/v1/audit-entries/export"))) {
            byte[] bytes = call(request.param("format", "xlsx").param("columns", "action"), OTHER, "ADMIN").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) { assertThat(book.getSheetAt(0).getLastRowNum()).isBetween(1, 2); }
        }
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> provider.dataset("audit-entries")).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
            var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fictional").header("alg", "none").subject("instructor").build();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, List.of(() -> "ROLE_INSTRUCTOR")));
            try {
                assertThatThrownBy(() -> provider.dataset("audit-entries")).isInstanceOfSatisfying(com.agilityhub.core.shared.domain.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(com.agilityhub.core.shared.domain.ErrorCode.FORBIDDEN));
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        }
    }
    @Test void T_14_14_parametersAndLevelReturnLatestEntityChangeOrExplicitNull() throws Exception {
        String key = "bookings.lateCancelThresholdMinutes";
        var parameters = json(admin(get("/api/v1/parameters")));
        assertThat(parameters.has("lastChange")).isTrue(); assertThat(parameters.path("lastChange").isNull()).isTrue();
        mongo.insert(new Document("_id", "audit-level").append("clubId", CLUB).append("code", "A").append("name", new Document("values", new Document("en", "Example").append("ca", "Exemple")).append("defaultLocale", "ca"))
                .append("order", 1).append("color", "#123456").append("capacity", 6).append("grantsFreeTraining", false).append("active", true).append("version", 0), "levels");
        var level = json(admin(get("/api/v1/levels/audit-level"))); assertThat(level.has("lastChange")).isTrue(); assertThat(level.path("lastChange").isNull()).isTrue();
        clock.setInstant(Instant.parse("2026-08-10T10:00:00Z"));
        admin(put("/api/v1/parameters/" + key).contentType("application/json").content("{\"value\":120,\"version\":0,\"reason\":\"Example update\"}")).andExpect(status().isOk());
        String parameterId = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("key").is(key)), Document.class, "parameters").getString("_id");
        for (String[] target : List.of(new String[]{"Parameter", parameterId}, new String[]{"Level", "audit-level"})) {
            mongo.insert(entry("latest-" + target[0], CLUB, "CATALOG_CHANGED", "2026-08-12T10:00:00Z").append("entityType", target[0]).append("entityId", target[1]).append("actorName", "Latest Admin"), "audit_entries");
            mongo.insert(entry("older-" + target[0], CLUB, "CLUB_UPDATED", "2026-08-11T10:00:00Z").append("entityType", target[0]).append("entityId", target[1]), "audit_entries");
            mongo.insert(entry("foreign-" + target[0], OTHER, "CLUB_UPDATED", "2026-08-13T10:00:00Z").append("entityType", target[0]).append("entityId", target[1]), "audit_entries");
        }
        for (String path : List.of("/api/v1/parameters", "/api/v1/levels/audit-level")) {
            var last = json(admin(get(path))).path("lastChange");
            assertThat(last.path("at").asText()).isEqualTo("2026-08-12T10:00:00Z");
            assertThat(last.path("actorName").asText()).isEqualTo("Latest Admin"); assertThat(last.path("action").asText()).isEqualTo("CATALOG_CHANGED");
        }
        var blocks = json(admin(get("/api/v1/parameters"))).path("blocks");
        assertThat(blocks.findValues("lastChange")).anySatisfy(last -> assertThat(last.path("actorName").asText()).isEqualTo("Latest Admin"));
    }
    /**
     * R-14-11 (`q` over `entityLabel`), S14 §3 (`entityLabel` snapshot) and R-14-10 (`eventIds[]` of the same transaction's outbox):
     * a parameter change made through the API is found by its key, and its entry links the `ParameterChanged` it published.
     * INC-38 (E11-T10).
     */
    @Test void T_14_11_INC_38_anEntryWrittenThroughTheApiIsFoundByItsLabelAndLinksItsEvents() throws Exception {
        String key = "bookings.lateCancelThresholdMinutes";
        admin(put("/api/v1/parameters/" + key).contentType("application/json").content("{\"value\":120,\"version\":0,\"reason\":\"Example update\"}")).andExpect(status().isOk());
        var page = json(admin(get("/api/v1/audit-entries").param("q", "lateCancelThreshold")));
        assertThat(page.path("totalItems").asInt()).isEqualTo(1);
        var item = page.path("items").get(0);
        assertThat(item.path("action").asText()).isEqualTo("PARAMETER_CHANGED"); assertThat(item.path("entityLabel").asText()).isEqualTo(key);
        var event = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("ParameterChanged")), Document.class, "domain_events");
        var detail = json(admin(get("/api/v1/audit-entries/" + item.path("id").asText())));
        assertThat(detail.path("eventIds")).hasSize(1); assertThat(detail.path("eventIds").get(0).asText()).isEqualTo(event.getString("_id"));
    }
    @Test void T_14_16_5001AuditRowsQueueAndWorkerCompletesMaskedExport() throws Exception {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "audit_entries");
        var rows = new ArrayList<Document>();
        for (int i = 0; i < 5001; i++) { rows.add(entry("bulk-" + i, CLUB, "MEMBER_UPDATED", "2026-08-01T10:00:00Z")); }
        mongo.insert(rows, "audit_entries");
        var accepted = admin(post("/api/v1/audit-entries/export").param("format", "xlsx").param("columns", "action,details"))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String id = mapper.readTree(accepted.getContentAsString()).path("jobId").asText();
        admin(get("/api/v1/exports/" + id)).andExpect(jsonPath("$.status").value("QUEUED"));
        worker.poll();
        var ready = json(admin(get("/api/v1/exports/" + id)));
        assertThat(ready.path("status").asText()).isEqualTo("READY"); assertThat(ready.path("rows").asInt()).isEqualTo(5001);
        byte[] bytes = admin(get(ready.path("downloadUrl").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(5001);
            assertThat(book.getSheetAt(0).getRow(5001).getCell(1).getStringCellValue()).contains("[ocult]").doesNotContain("ES0000", "fictional-password-value");
        }
    }
}
