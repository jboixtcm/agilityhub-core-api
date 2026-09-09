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
class ExportEngineIT extends AbstractIntegrationTest {
    static final String CLUB = "export-a", OTHER = "export-b";
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired com.agilityhub.core.identity.application.TokenService tokens;
    @Autowired com.agilityhub.core.identity.application.PasswordHasher passwords;
    @Autowired org.springframework.data.mongodb.MongoTransactionManager transactions;
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @BeforeEach void prepare() {
        TenantContext.clear();
        for (String collection : List.of("members", "dogs", "plans", "prices", "levels", "dog_documents", "memberships", "saved_views", "export_jobs", "export_write_locks", "audit_entries", "domain_events")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String club : List.of(CLUB, OTHER)) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(PlatformFixtures.club(club, club + ".example.test"));
            tree.set("modules", mapper.valueToTree(Module.values())); clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(club);
        }
        hosts.invalidate();
        mongo.insert(new Document("_id", "export-plan-a").append("clubId", CLUB).append("name", new Document("ca", "Modalitat").append("es", "Modalidad").append("en", "Plan")), "plans");
        mongo.insert(new Document("_id", "export-level-a").append("clubId", CLUB).append("code", "A").append("name", new Document("values", new Document("ca", "Inici").append("es", "Inicio"))).append("order", 1).append("grantsFreeTraining", true), "levels");
        var members = new ArrayList<Document>();
        for (int i = 0; i < 60; i++) { members.add(member(i)); }
        mongo.insert(members, "members");
        mongo.insert(member(90).append("_id", "export-other-member").append("clubId", OTHER), "members");
        mongo.insert(dog("export-dog-a", "Duna", CLUB), "dogs");
        mongo.insert(dog("export-dog-b", "Other tenant dog", OTHER).append("levelId", "export-level-a"), "dogs");
        mongo.insert(new Document("_id", "export-doc-a").append("clubId", CLUB).append("dogId", "export-dog-a").append("type", "VACCINATION_CARD").append("state", "PENDING"), "dog_documents");
    }
    Document member(int i) {
        return new Document("_id", "export-member-" + i).append("clubId", CLUB).append("memberNumber", i).append("firstName", "Fictional")
                .append("lastName1", String.format("Example %03d", i)).append("lastName2", "").append("status", i % 2 == 0 ? "ACTIVE" : "LEFT")
                .append("planId", "export-plan-a").append("priceId", "export-price-a").append("birthDate", "2000-05-06").append("gender", "OTHER")
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
        return new Document("_id", id).append("clubId", club).append("memberId", "export-member-0").append("name", name).append("breed", "Example")
                .append("handlerName", "Fictional Handler").append("status", "ACTIVE").append("sex", "FEMALE").append("chip", "000000000000001")
                .append("birthDate", "2022-03-12").append("levelId", "export-level-a").append("registeredAt", Date.from(Instant.parse("2025-01-01T00:00:00Z")))
                .append("licenses", List.of(new Document("organisation", "Example Federation").append("number", "123"))).append("version", 0);
    }
    String requesterLocale = "es";
    ResultActions call(MockHttpServletRequestBuilder request, String club, String account, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject(account).claim("clubId", club).claim("locale", requesterLocale).claim("name", "Example Admin"))
                .authorities(() -> "ROLE_" + role)));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "export-admin-a", "ADMIN"); }
    JsonNode json(ResultActions result, int code) throws Exception { return mapper.readTree(result.andExpect(status().is(code)).andReturn().getResponse().getContentAsString()); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object input) throws Exception { return request.contentType("application/json").content(mapper.writeValueAsBytes(input)); }
    @Autowired com.agilityhub.core.clubs.common.application.ExportWorker worker;
    @Autowired com.agilityhub.core.clubs.common.application.ExportJobCoordinator coordinator;
    @Autowired com.agilityhub.core.clubs.common.application.ExportJobRenderer renderer;
    @Autowired com.agilityhub.core.clubs.common.persistence.ListExportRepository jobs;
    @Autowired com.agilityhub.core.clubs.common.persistence.LocalExportStorage storage;

    @AfterEach void cleanExportFixtures() {
        for (String collection : List.of("members", "dogs", "plans", "prices", "levels", "dog_documents", "memberships", "export_jobs", "audit_entries", "domain_events")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
    }
    @Test void T_14_16_completionRollsBackWithAuditAndOutbox() throws Exception {
        String id = largeExport();
        try (var tenant = TenantContext.open(CLUB)) {
            var claim = coordinator.claim();
            var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fictional").header("alg", "none").subject("export-admin-a").claim("clubId", CLUB).build();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, List.of(() -> "ROLE_ADMIN")));
            try {
                assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                    coordinator.complete(claim, "unused", 10, 5001); throw new IllegalStateException("Deliberate rollback");
                })).hasMessage("Deliberate rollback");
                assertThat(jobs.findById(id).orElseThrow().status()).isEqualTo("RUNNING");
                assertThat(mongo.count(Query.query(Criteria.where("aggregateId").is(id)), "domain_events")).isZero();
                assertThat(mongo.count(Query.query(Criteria.where("entityId").is(id)), "audit_entries")).isZero();
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        }
    }
    @Test @AuditCovers(AuditAction.DATA_EXPORTED)
    void T_14_15_localizedHeadersMaskedValuesDatesAndReadyJobs() throws Exception {
        clock.setInstant(Instant.parse("2026-08-10T07:12:00Z"));
        for (String locale : List.of("ca", "es", "en")) {
            requesterLocale = locale;
            var response = admin(get("/api/v1/members/export").header("Accept-Language", locale).param("format", "xlsx").param("columns", "fullName,paymentMethod,nextInvoiceDate"))
                    .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"export-a_members_20260810-0912.xlsx\""))
                    .andReturn().getResponse();
            try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                var sheet = workbook.getSheetAt(0); assertThat(sheet.getLastRowNum()).isEqualTo(60);
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(Map.of("ca", "Abonat", "es", "Socio", "en", "Member").get(locale));
                assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo(Map.of("ca", "Pagament", "es", "Pago", "en", "Payment").get(locale));
                assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).contains("2231").doesNotContain("ES0000");
                assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("01/10/2026");
            }
        }
        requesterLocale = "ca";
        var pdf = admin(get("/api/v1/members/export").header("Accept-Language", "ca").param("format", "pdf").param("columns", "fullName,paymentMethod"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("Pàgina 1 de", "Abonat", "Pagament").doesNotContain("ES0000");
        }
        var list = json(admin(get("/api/v1/exports").param("kind", "LIST")), 200);
        assertThat(list).hasSize(4); assertThat(list.get(0).path("status").asText()).isEqualTo("READY");
        assertThat(list.toString()).doesNotContain("fileKey", "query", "ownerAccountId", "claimToken");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("DataExported")), "domain_events")).isEqualTo(4);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("DATA_EXPORTED")), "audit_entries")).isEqualTo(4);
    }
    String largeExport() throws Exception {
        requesterLocale = "ca";
        if (mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "dogs") < 5001) {
            mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "dogs");
            var rows = new ArrayList<Document>();
            for (int i = 0; i < 5001; i++) { rows.add(dog("export-dog-" + i, String.format("Dog %05d", i), CLUB)); }
            mongo.insert(rows, "dogs");
        }
        return json(admin(post("/api/v1/dogs/export").header("Accept-Language", "ca").param("format", "xlsx").param("columns", "name,chip")), 202).path("jobId").asText();
    }
    @Test void T_14_16_asyncSignedDownloadOwnershipExpiryAndCleanup() throws Exception {
        String id = largeExport();
        admin(get("/api/v1/exports/" + id)).andExpect(jsonPath("$.status").value("QUEUED"));
        com.agilityhub.core.clubs.common.persistence.ExportJob claim;
        try (var tenant = TenantContext.open(CLUB)) { claim = coordinator.claim(); }
        admin(get("/api/v1/exports/" + id)).andExpect(jsonPath("$.status").value("RUNNING"));
        assertThat(mongo.count(Query.query(Criteria.where("aggregateId").is(id)), "domain_events")).isZero();
        renderer.render(claim, false);
        requesterLocale = "en";
        var ready = json(admin(get("/api/v1/exports/" + id).header("Accept-Language", "en")), 200);
        assertThat(ready.path("status").asText()).isEqualTo("READY"); assertThat(ready.path("rows").asLong()).isEqualTo(5001);
        assertThat(ready.path("expiresAt").asText()).isEqualTo(clock.instant().plus(java.time.Duration.ofDays(7)).toString());
        String url = ready.path("downloadUrl").asText(); assertThat(url).contains("signature=");
        var bytes = admin(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(5001);
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Gos");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).contains("001").doesNotContain("000000000000001");
        }
        for (String path : List.of("/api/v1/exports/" + id, url)) {
            call(get(path), CLUB, "other-admin", "ADMIN").andExpect(status().isNotFound());
            call(get(path), OTHER, "export-admin-a", "ADMIN").andExpect(status().isNotFound());
            call(get(path), CLUB, "instructor", "INSTRUCTOR").andExpect(status().isForbidden());
            call(get(path), CLUB, "member-other", "MEMBER").andExpect(status().isNotFound());
            call(get(path), CLUB, "export-admin-a", "MEMBER").andExpect(status().isOk());
        }
        call(get("/api/v1/exports"), CLUB, "member", "MEMBER").andExpect(status().isForbidden());
        call(get("/api/v1/exports"), OTHER, "export-admin-a", "ADMIN").andExpect(jsonPath("$.length()").value(0));
        admin(get(url.substring(0, url.indexOf("signature=")) + "signature=invalid")).andExpect(status().isForbidden());
        worker.poll();
        assertThat(mongo.count(Query.query(Criteria.where("aggregateId").is(id).and("type").is("DataExported")), "domain_events")).isEqualTo(1);
        String key = mongo.findById(id, Document.class, "export_jobs").getString("fileKey");
        clock.advance(java.time.Duration.ofDays(7));
        admin(get("/api/v1/exports/" + id)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXPORT_EXPIRED"));
        admin(get(url)).andExpect(status().isUnprocessableEntity());
        worker.poll();
        assertThat(mongo.findById(id, Document.class, "export_jobs").getString("status")).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
        admin(get("/api/v1/exports")).andExpect(jsonPath("$[0].status").value("EXPIRED"));
        assertThat(TenantContext.current()).isNull();
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void T_14_16_twoRunningPerClubAndConcurrentClaimsAreFenced() throws Exception {
        String id = largeExport(); largeExport(); largeExport();
        var claims = new ArrayList<java.util.concurrent.Future<com.agilityhub.core.clubs.common.persistence.ExportJob>>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) { claims.add(pool.submit(() -> { try (var tenant = TenantContext.open(CLUB)) { return coordinator.claim(); } })); }
            var running = new ArrayList<com.agilityhub.core.clubs.common.persistence.ExportJob>();
            for (var future : claims) { var job = future.get(); if (job != null) { running.add(job); } }
            assertThat(running).hasSize(2).extracting(com.agilityhub.core.clubs.common.persistence.ExportJob::id).doesNotHaveDuplicates();
            admin(post("/api/v1/dogs/export").param("format", "xlsx")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXPORT_LIMIT"));
            call(post("/api/v1/dogs/export").param("format", "xlsx"), OTHER, "export-admin-b", "ADMIN").andExpect(status().isOk());
            clock.advance(java.time.Duration.ofMinutes(6));
            try (var tenant = TenantContext.open(CLUB)) {
                var reclaimed = coordinator.claim(); assertThat(reclaimed).isNotNull();
                assertThatThrownBy(() -> coordinator.complete(running.getFirst(), "stale", 1, 1)).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
            }
        }
    }
    @Test void T_14_16_failureRetriesThreeTimesAndLegacyHandoffRenders() throws Exception {
        String id = largeExport();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset("fileName").unset("timeZone").unset("expiresAt"), "export_jobs");
        worker.poll();
        admin(get("/api/v1/exports/" + id)).andExpect(jsonPath("$.status").value("READY"));
        String bad = largeExport();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(bad)), new Update().set("columns", List.of("invalid-column")), "export_jobs");
        for (int attempt = 1; attempt <= 3; attempt++) { worker.poll(); }
        admin(get("/api/v1/exports/" + bad)).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.error.code").value("INVALID_FILTER"));
        assertThat(mongo.findById(bad, Document.class, "export_jobs").getInteger("attempts")).isEqualTo(3);
        assertThat(mongo.count(Query.query(Criteria.where("aggregateId").is(bad)), "domain_events")).isZero();
        admin(get("/api/v1/exports/" + bad + "/download").param("expires", "0").param("signature", "invalid")).andExpect(status().isConflict());
        worker.poll();
    }
    @Test void T_14_16_rateLimitAndResourceAuthorization() throws Exception {
        for (int i = 0; i < 10; i++) { admin(post("/api/v1/dogs/export").param("format", "xlsx").param("q", "no-results")).andExpect(status().isOk()); }
        admin(post("/api/v1/dogs/export").param("format", "xlsx")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        call(post("/api/v1/dogs/export").param("format", "xlsx"), OTHER, "export-admin-a", "ADMIN").andExpect(status().isTooManyRequests());
        clock.advance(java.time.Duration.ofMinutes(10));
        admin(post("/api/v1/dogs/export").param("format", "xlsx")).andExpect(status().isOk());
        for (String resource : List.of("members", "dogs")) {
            for (String role : List.of("MEMBER", "INSTRUCTOR")) { call(post("/api/v1/" + resource + "/export").param("format", "xlsx"), CLUB, "reader", role).andExpect(status().isForbidden()); }
            mvc.perform(post("/api/v1/" + resource + "/export").param("format", "xlsx").header("Host", OTHER + ".example.test")
                    .with(jwt().jwt(j -> j.subject("export-admin-a").claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN"))).andExpect(status().isForbidden());
        }
    }
    @Test void T_14_16_lifecycleRoutesRejectAnonymousAndImpersonatedRequests() throws Exception {
        admin(post("/api/v1/members/export").param("format", "xlsx")).andExpect(status().isOk());
        String id = json(admin(get("/api/v1/exports")), 200).get(0).path("id").asText();
        for (String path : List.of("/api/v1/exports", "/api/v1/exports/" + id, "/api/v1/exports/" + id + "/download?expires=0&signature=invalid")) {
            mvc.perform(get(path).header("Host", CLUB + ".example.test")).andExpect(status().isUnauthorized());
            call(get(path), CLUB, "instructor", "INSTRUCTOR").andExpect(status().isForbidden());
            // A forged impersonation claim has no stored grant and is rejected during authentication.
            mvc.perform(get(path).header("Host", CLUB + ".example.test").with(jwt().jwt(j -> j.subject("export-admin-a").claim("clubId", CLUB).claim("imp", true))
                    .authorities(() -> "ROLE_ADMIN"))).andExpect(status().isUnauthorized());
        }
    }
    @Test void T_14_16_100001RowsRejectBeforeCreatingAJob() throws Exception {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "dogs");
        var rows = new ArrayList<Document>();
        for (int i = 0; i < 100001; i++) { rows.add(new Document("_id", "too-large-" + i).append("clubId", CLUB).append("name", "Dog " + i).append("status", "ACTIVE")); }
        mongo.insert(rows, "dogs");
        admin(post("/api/v1/dogs/export").param("format", "xlsx")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "export_jobs")).isZero();
    }
}
