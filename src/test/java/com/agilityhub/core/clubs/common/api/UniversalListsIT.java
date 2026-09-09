package com.agilityhub.core.clubs.common.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "shared.scheduling.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class UniversalListsIT extends AbstractIntegrationTest {
    static final String CLUB = "list-a", OTHER = "list-b";
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired com.agilityhub.core.identity.application.TokenService tokens;
    @Autowired com.agilityhub.core.identity.application.PasswordHasher passwords;
    @Autowired org.springframework.data.mongodb.MongoTransactionManager transactions;
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @BeforeEach void prepare() {
        TenantContext.clear();
        for (String collection : List.of("members", "dogs", "plans", "prices", "levels", "dog_documents", "memberships", "saved_views", "export_jobs", "audit_entries", "domain_events")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String club : List.of(CLUB, OTHER)) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(PlatformFixtures.club(club, club + ".example.test"));
            tree.set("modules", mapper.valueToTree(Module.values())); clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(club);
        }
        hosts.invalidate();
        mongo.insert(new Document("_id", "plan-a").append("clubId", CLUB).append("name", new Document("ca", "Modalitat").append("es", "Modalidad").append("en", "Plan")), "plans");
        mongo.insert(new Document("_id", "level-a").append("clubId", CLUB).append("code", "A").append("name", new Document("values", new Document("ca", "Inici").append("es", "Inicio"))).append("order", 1).append("grantsFreeTraining", true), "levels");
        var members = new ArrayList<Document>();
        for (int i = 0; i < 60; i++) { members.add(member(i)); }
        mongo.insert(members, "members");
        mongo.insert(member(90).append("_id", "other-member").append("clubId", OTHER), "members");
        mongo.insert(dog("dog-a", "Duna", CLUB), "dogs");
        mongo.insert(dog("dog-b", "Other tenant dog", OTHER).append("levelId", "level-a"), "dogs");
        mongo.insert(new Document("_id", "doc-a").append("clubId", CLUB).append("dogId", "dog-a").append("type", "VACCINATION_CARD").append("state", "PENDING"), "dog_documents");
    }
    Document member(int i) {
        return new Document("_id", "member-" + i).append("clubId", CLUB).append("memberNumber", i).append("firstName", "Fictional")
                .append("lastName1", String.format("Example %03d", i)).append("lastName2", "").append("status", i % 2 == 0 ? "ACTIVE" : "LEFT")
                .append("planId", "plan-a").append("priceId", "price-a").append("birthDate", "2000-05-06").append("gender", "OTHER")
                .append("address", new Document("city", i < 30 ? "Town A" : "Town B").append("postalCode", "08001").append("street", "Example Street"))
                .append("contactEmails", List.of(new Document("email", "person" + i + "@example.test").append("bounced", false)))
                .append("phones", List.of(new Document("prefix", "+34").append("number", "600000000").append("label", "Example")))
                .append("bookingBlock", new Document("active", false).append("reason", "Internal reason"))
                .append("consents", new Document("imageRights", new Document("granted", true)))
                .append("idDocument", new Document("type", "PASSPORT").append("number", "AB123456CD"))
                .append("paymentMethod", new Document("type", "SEPA_DD").append("sepa", new Document("iban", "ES0000000000000000002231").append("holderName", "Fictional Holder")))
                .append("nextInvoiceDate", "2026-10-01").append("joinedAt", Date.from(Instant.parse("2025-01-01T00:00:00Z"))).append("version", 0);
    }
    Document dog(String id, String name, String club) {
        return new Document("_id", id).append("clubId", club).append("memberId", "member-0").append("name", name).append("breed", "Example")
                .append("handlerName", "Fictional Handler").append("status", "ACTIVE").append("sex", "FEMALE").append("chip", "000000000000001")
                .append("birthDate", "2022-03-12").append("levelId", "level-a").append("registeredAt", Date.from(Instant.parse("2025-01-01T00:00:00Z")))
                .append("licenses", List.of(new Document("organisation", "Example Federation").append("number", "123"))).append("version", 0);
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String account, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject(account).claim("clubId", club).claim("locale", "es").claim("name", "Example Admin"))
                .authorities(() -> "ROLE_" + role)));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "admin-a", "ADMIN"); }
    JsonNode json(ResultActions result, int code) throws Exception { return mapper.readTree(result.andExpect(status().is(code)).andReturn().getResponse().getContentAsString()); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object input) throws Exception { return request.contentType("application/json").content(mapper.writeValueAsBytes(input)); }
    @Test void T_03_08_memberPagingSearchSelectionAndTenantJoins() throws Exception {
        var page = json(admin(get("/api/v1/members").param("filter", "status:eq:ACTIVE").param("size", "20").param("sort", "lastName,asc")), 200);
        assertThat(page.path("items")).hasSize(20); assertThat(page.path("totalItems").asInt()).isEqualTo(30);
        assertThat(page.at("/items/0/fullName").asText()).isEqualTo("Fictional Example 000");
        assertThat(page.at("/appliedFilters/0/op").asText()).isEqualTo("eq");
        admin(get("/api/v1/members").param("filter", "foo:eq:1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        admin(get("/api/v1/members").param("q", "Duna")).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].dogs.length()").value(1));
        admin(get("/api/v1/members").param("q", "Other tenant dog")).andExpect(jsonPath("$.totalItems").value(0));
        admin(get("/api/v1/members").param("q", ".*")).andExpect(jsonPath("$.totalItems").value(0));
        admin(get("/api/v1/members").param("filter", "status:eq:ACTIVE", "city:eq:Town A").param("sort", "memberNumber,desc").param("fields", "id,fullName"))
                .andExpect(jsonPath("$.totalItems").value(15)).andExpect(jsonPath("$.items[0].id").value("member-28")).andExpect(jsonPath("$.items[0].length()").value(2));
        admin(get("/api/v1/members").param("filter", "id:in:member-0,member-2")).andExpect(jsonPath("$.totalItems").value(2));
        admin(get("/api/v1/members").param("page", "100")).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.totalItems").value(60));
        call(get("/api/v1/dogs"), OTHER, "admin-b", "ADMIN").andExpect(jsonPath("$.items[0].level.id").doesNotExist());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource(value = {"memberNumber:eq:2|1", "memberNumber:ne:2|59", "memberNumber:in:2,4|2", "memberNumber:nin:2,4|58", "memberNumber:lt:2|2", "memberNumber:lte:2|3", "memberNumber:gt:58|1", "memberNumber:gte:58|2", "memberNumber:between:2,4|3", "lastName:startsWith:Example 00|10", "joinedAt:gte:2025-01-01T00:00:00Z|60", "nextInvoiceDate:eq:2026-10-01|60", "leaveDate:exists:false|60"}, delimiter = '|')
    void T_03_08_allSupportedMongoOperatorsExecute(String filter, int expected) throws Exception {
        admin(get("/api/v1/members").param("filter", filter)).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(expected));
    }
    @Test void T_03_09_facetsApplyOtherFiltersAndResolveLocalizedReferenceNames() throws Exception {
        admin(get("/api/v1/members/filter-values").param("field", "planId").param("filter", "status:eq:ACTIVE", "planId:eq:missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.values[0].label").value("Modalidad")).andExpect(jsonPath("$.values[0].count").value(30));
        admin(get("/api/v1/members/filter-values").param("field", "city").param("q", "Duna")).andExpect(jsonPath("$.values[0].count").value(1));
        admin(get("/api/v1/members/filter-values").param("field", "memberNumber")).andExpect(jsonPath("$.values.length()").value(50));
        admin(get("/api/v1/dogs/filter-values").param("field", "levelId")).andExpect(jsonPath("$.values[0].label").value("Inicio"));
        admin(get("/api/v1/dogs/filter-values").param("field", "bad")).andExpect(status().isBadRequest());
    }
    @Test void T_03_08_dogQueriesAndRoleModuleRestrictions() throws Exception {
        admin(get("/api/v1/dogs").param("q", "Example 000").param("filter", "handlerName:contains:Handler", "hasPendingDocuments:eq:true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].freeTraining.allowed").value(true));
        var dogRow = json(admin(get("/api/v1/dogs")), 200).path("items").get(0);
        assertThat(dogRow.path("freeTraining").has("override")).isTrue();
        assertThat(dogRow.path("freeTraining").path("override").isNull()).isTrue();
        var response = json(call(get("/api/v1/members"), CLUB, "instructor-a", "INSTRUCTOR"), 200).path("items").get(0);
        assertThat(response.has("paymentMethod")).isFalse(); assertThat(response.has("imageRights")).isFalse();
        assertThat(response.has("contactEmails")).isTrue(); assertThat(response.toString()).doesNotContain("Internal reason", "2231");
        for (String path : List.of("/api/v1/members", "/api/v1/members/filter-values")) {
            call(get(path).param("field", "planId").param("filter", "planId:eq:plan-a"), CLUB, "instructor-a", "INSTRUCTOR").andExpect(status().isBadRequest());
        }
        call(get("/api/v1/members").param("fields", "paymentMethod"), CLUB, "instructor-a", "INSTRUCTOR").andExpect(status().isBadRequest());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow());
        tree.set("modules", mapper.createArrayNode()); clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
        var row = json(admin(get("/api/v1/members")), 200).path("items").get(0);
        assertThat(row.has("plan")).isFalse(); assertThat(row.has("paymentMethod")).isFalse(); assertThat(row.has("familyGroup")).isFalse(); assertThat(row.has("freeTraining")).isFalse();
    }
    Map<String, Object> view(String name, boolean shared) { return Map.of("listKey", "members", "name", name, "columns", List.of("fullName"), "filters", List.of(Map.of("field", "status", "op", "eq", "value", "ACTIVE")), "sort", List.of("lastName,asc"), "shared", shared); }
    @Test void T_03_10_savedViewsOwnershipSharingUniquenessVersionAndTenantIsolation() throws Exception {
        String id = json(admin(body(post("/api/v1/saved-views"), view("Shared", true))), 201).path("id").asText();
        admin(body(post("/api/v1/saved-views"), view("Shared", true))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAVED_VIEW_NAME_TAKEN"));
        call(get("/api/v1/saved-views").param("listKey", "members"), CLUB, "instructor-a", "INSTRUCTOR").andExpect(jsonPath("$[0].id").value(id));
        call(get("/api/v1/saved-views/" + id), CLUB, "instructor-a", "INSTRUCTOR").andExpect(status().isOk());
        var update = new HashMap<>(view("Renamed", true)); update.put("version", 0);
        call(body(put("/api/v1/saved-views/" + id), update), CLUB, "instructor-a", "INSTRUCTOR").andExpect(status().isForbidden());
        call(delete("/api/v1/saved-views/" + id), CLUB, "instructor-a", "INSTRUCTOR").andExpect(status().isForbidden());
        call(get("/api/v1/saved-views/" + id), OTHER, "admin-a", "ADMIN").andExpect(status().isNotFound());
        call(body(put("/api/v1/saved-views/" + id), update), OTHER, "admin-a", "ADMIN").andExpect(status().isNotFound());
        call(delete("/api/v1/saved-views/" + id), OTHER, "admin-a", "ADMIN").andExpect(status().isNotFound());
        admin(body(put("/api/v1/saved-views/" + id), update)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        admin(body(put("/api/v1/saved-views/" + id), update)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().push("data.columns", "retired-column"), "saved_views");
        admin(get("/api/v1/saved-views/" + id)).andExpect(jsonPath("$.columns.length()").value(1));
        admin(delete("/api/v1/saved-views/" + id)).andExpect(status().isNoContent());
        admin(get("/api/v1/saved-views/" + id)).andExpect(status().isNotFound());
        String own = json(call(body(post("/api/v1/saved-views"), view("Own", false)), CLUB, "instructor-a", "INSTRUCTOR"), 201).path("id").asText();
        call(get("/api/v1/saved-views/" + own), CLUB, "instructor-b", "INSTRUCTOR").andExpect(status().isNotFound());
        call(body(put("/api/v1/saved-views/" + own), update), CLUB, "instructor-a", "INSTRUCTOR").andExpect(status().isOk());
        call(delete("/api/v1/saved-views/" + own), CLUB, "admin-b", "ADMIN").andExpect(status().isNoContent());
    }
    @Test @AuditCovers(AuditAction.DATA_EXPORTED)
    void T_03_11_exportsMaskFilesAndWriteTransactionalAuditOutbox() throws Exception {
        var response = admin(get("/api/v1/members/export").param("format", "xlsx").param("columns", "fullName,paymentMethod").param("filter", "id:in:member-0,member-2").param("page", "9").param("fields", "id"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0); assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Socio");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).contains("···· 2231").doesNotContain("ES0000");
        }
        var event = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("DataExported")), Document.class, "domain_events");
        assertThat(event).isNotNull(); assertThat(event.toJson()).contains("rows", "2").doesNotContain("ES0000");
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("DATA_EXPORTED")), Document.class, "audit_entries");
        assertThat(audit).isNotNull(); assertThat(audit.toJson()).contains("listKey", "format", "rows").doesNotContain("ES0000");
        byte[] pdf = admin(get("/api/v1/members/export").param("format", "pdf").param("columns", "fullName,paymentMethod").param("filter", "id:in:member-0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains("Fictional Example 000", "2231", "members").doesNotContain("ES0000");
        }
        admin(get("/api/v1/dogs/export").param("format", "xlsx")).andExpect(status().isOk());
        admin(get("/api/v1/dogs/export").param("format", "pdf")).andExpect(status().isOk());
        admin(get("/api/v1/members/export").param("format", "csv")).andExpect(status().isBadRequest());
        admin(get("/api/v1/members/export").param("format", "xlsx").param("columns", "iban")).andExpect(status().isBadRequest());
    }
    @Test void T_03_11_5000InlineAnd5001DurableHandoff() throws Exception {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "members");
        var rows = new ArrayList<Document>(); for (int i = 0; i < 5000; i++) { rows.add(member(i)); } mongo.insert(rows, "members");
        admin(get("/api/v1/members/export").param("format", "xlsx").param("columns", "fullName")).andExpect(status().isOk());
        mongo.insert(member(5001), "members");
        var accepted = json(admin(get("/api/v1/members/export").param("format", "xlsx").param("columns", "fullName").param("sort", "memberNumber,desc")), 202);
        var job = mongo.findById(accepted.path("jobId").asText(), Document.class, "export_jobs");
        assertThat(job).containsEntry("clubId", CLUB).containsEntry("status", "QUEUED").containsEntry("ownerAccountId", "admin-a");
        assertThat(job.getList("columns", String.class)).containsExactly("fullName");
        assertThat(accepted.path("statusUrl").asText()).endsWith(accepted.path("jobId").asText());
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("DataExported")), "domain_events")).isEqualTo(1);
    }
    @Test void T_03_08_T_03_10_T_03_11_allImplementedEndpointsEnforceRolesAndHostTenant() throws Exception {
        for (String path : List.of("/api/v1/members", "/api/v1/dogs", "/api/v1/members/filter-values", "/api/v1/dogs/filter-values", "/api/v1/members/export", "/api/v1/dogs/export", "/api/v1/saved-views")) {
            var request = get(path).param("format", "xlsx").param("field", "status").param("listKey", "members");
            call(request, CLUB, "member-a", "MEMBER").andExpect(status().isForbidden());
            mvc.perform(get(path).header("Host", CLUB + ".example.test")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Host", OTHER + ".example.test").with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
            if (path.endsWith("export")) { call(get(path).param("format", "xlsx"), CLUB, "instructor-a", "INSTRUCTOR").andExpect(status().isForbidden()); }
        }
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            var request = request(org.springframework.http.HttpMethod.valueOf(method), method.equals("POST") ? "/api/v1/saved-views" : "/api/v1/saved-views/id");
            var data = new HashMap<>(view("Example", false)); data.put("version", 0);
            call(body(request, data), CLUB, "member-a", "MEMBER").andExpect(status().isForbidden());
        }
    }
    @Test void T_03_11_exportAuditAndOutboxRollbackTogether() {
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            try { admin(get("/api/v1/members/export").param("format", "xlsx").param("columns", "fullName")).andExpect(status().isOk()); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            throw new IllegalStateException("Deliberate rollback");
        })).hasMessage("Deliberate rollback");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("DataExported")), "domain_events")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("DATA_EXPORTED")), "audit_entries")).isZero();
    }
    @Test void T_03_10_savedViewsValidateDefinitionAndScopeUniquenessByClub() throws Exception {
        String id = json(admin(body(post("/api/v1/saved-views"), view("Same name", false))), 201).path("id").asText();
        var other = json(call(body(post("/api/v1/saved-views"), view("Same name", false)), OTHER, "admin-a", "ADMIN"), 201);
        assertThat(other.path("id").asText()).isNotEqualTo(id);
        call(get("/api/v1/saved-views").param("listKey", "members"), OTHER, "admin-a", "ADMIN").andExpect(jsonPath("$.length()").value(1));
        for (Object invalidColumns : List.of(List.of(), List.of("unknown"), List.of("fullName", "fullName"))) {
            var input = new HashMap<>(view("Invalid", false)); input.put("columns", invalidColumns);
            admin(body(post("/api/v1/saved-views"), input)).andExpect(status().isBadRequest());
        }
        var input = new HashMap<>(view("Financial", true)); input.put("columns", List.of("paymentMethod")); input.put("filters", List.of(Map.of("field", "planId", "op", "eq", "value", "plan-a"))); input.put("sort", List.of("nextInvoiceDate,asc"));
        String financial = json(admin(body(post("/api/v1/saved-views"), input)), 201).path("id").asText();
        call(get("/api/v1/saved-views/" + financial), CLUB, "instructor-a", "INSTRUCTOR")
                .andExpect(jsonPath("$.columns.length()").value(0)).andExpect(jsonPath("$.filters.length()").value(0)).andExpect(jsonPath("$.sort.length()").value(0));
        input = new HashMap<>(view("Invalid", false)); input.put("listKey", "unregistered");
        admin(body(post("/api/v1/saved-views"), input)).andExpect(status().isBadRequest());
        var collision = new HashMap<>(view("Financial", true)); collision.put("version", 0);
        admin(body(put("/api/v1/saved-views/" + id), collision)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAVED_VIEW_NAME_TAKEN"));
    }
    @Test void T_03_08_realCurlWithTwoFiltersAndSort() throws Exception {
        String account = "list-curl-account";
        mongo.remove(Query.query(Criteria.where("_id").is(account)), "accounts");
        mongo.insert(new com.agilityhub.core.identity.persistence.Account(account, "list-curl@example.test", "Fictional Curl Admin", "en", passwords.hash("Fictional-list-smoke-2030!"),
                Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, new com.agilityhub.core.identity.persistence.Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.insert(new com.agilityhub.core.identity.persistence.Membership("list-curl-membership", account, CLUB, "member-0",
                Set.of(com.agilityhub.core.identity.domain.Role.ADMIN), com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.ADMIN));
        String token;
        try (var tenant = TenantContext.open(CLUB)) { token = tokens.password("list-curl@example.test", "Fictional-list-smoke-2030!", "clubs-admin").access().getTokenValue(); }
        String url = "http://localhost:" + port + "/api/v1/members?filter=status:eq:ACTIVE&filter=city:eq:Town%20A&sort=lastName,desc&size=20&fields=id,fullName";
        var process = new ProcessBuilder("curl", "--silent", "--show-error", "--fail-with-body", "--include", "--config", "-", url).redirectErrorStream(true).start();
        try (var input = process.outputWriter()) { input.write("header = \"Host: list-a.example.test\"\nheader = \"Authorization: Bearer " + token + "\"\n"); }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("200", "Fictional Example 028", "totalItems\":15");
        String evidence = "curl --silent --show-error --fail-with-body --include -H 'Host: list-a.example.test' -H 'Authorization: Bearer eyJ…[truncated]' '" + url + "'\n" + output + "\nExit code: 0\n";
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/e2-t07-curl.txt"), evidence);
    }

}
