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
import org.springframework.boot.test.autoconfigure.web.servlet.*;
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
