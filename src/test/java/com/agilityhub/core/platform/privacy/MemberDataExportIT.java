package com.agilityhub.core.platform.privacy;

import com.agilityhub.core.clubs.common.application.ExportWorker;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** S14 R-14-13 / T-14-17 (E11-T01): the member's data package, `memberData.v1`, its files and N-50. */
class MemberDataExportIT extends PrivacyItFixtures {
    static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
    @Autowired ExportWorker worker; @Autowired AttachmentStorage storage;
    final String card = UUID.randomUUID().toString();

    @BeforeEach void seed() throws Exception {
        seedClubs();
        clock.setInstant(NOW);
        member(CLUB, "laura", 41, "12345678Z", null);
        member(CLUB, "pau", 42, "X1234567L", null);
        dog(CLUB, "duna", "laura", "941000000200001", null);
        dog(CLUB, "rock", "laura", "941000000200002", null);
        storage.put(card, "application/pdf", 4, new ByteArrayInputStream(new byte[] {37, 80, 68, 70}));
        raw("dog_documents", "_id", "doc-duna", "clubId", CLUB, "dogId", "duna", "type", "VACCINATION", "state", "VALID", "version", 0,
                "files", List.of(map("id", "f1", "fileKey", card, "name", "cartilla-duna.pdf", "mimeType", "application/pdf")));
        for (int i = 1; i <= 38; i++) {
            raw("invoices", "_id", "invoice-" + i, "clubId", CLUB, "memberId", "laura", "series", "2026", "number", (long) i, "displayNumber", "2026-" + i, "status", "PAID",
                    "total", map("amountMinor", 9000L, "currency", "EUR"), "lines", List.of(map("lineNo", 1, "description", "Quota")));
        }
        raw("invoices", "_id", "invoice-pau", "clubId", CLUB, "memberId", "pau", "series", "2026", "number", 99L, "displayNumber", "2026-99", "status", "PAID");
        raw("collections", "_id", "collection-1", "clubId", CLUB, "invoiceId", "invoice-1", "status", "SUCCEEDED");
        raw("audit_entries", "_id", "audit-1", "clubId", CLUB, "at", Instant.parse("2026-07-01T10:00:00Z"), "actorAccountId", "admin-account",
                "actorName", "Fictional Admin", "actorRole", "ADMIN", "action", "MEMBER_UPDATED", "entityType", "Member", "entityId", "laura", "memberId", "laura",
                "changes", List.of(), "traceId", "t", "origin", "BACKOFFICE");
        // A live impersonation of Laura by the admin (S01 R-01-11): its token may not ask for her data.
        raw("impersonation_grants", "_id", "grant-laura", "clubId", CLUB, "actorAccountId", "admin-account", "impersonatedMemberId", "laura",
                "impersonatedAccountId", "laura-account", "reason", "support", "expiresAt", NOW.plusSeconds(3600), "type", "IMPERSONATION");
        raw("notifications", "_id", "notification-1", "clubId", CLUB, "code", "N-13", "title", "Hola Laura", "body", "Duna puja", "createdAt", Instant.parse("2026-07-01T10:00:00Z"),
                "recipient", map("accountId", "laura-account", "memberId", "laura"), "deliveries", List.of(map("channel", "APP", "status", "DELIVERED")));
    }
    org.springframework.test.web.servlet.ResultActions member(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String account, boolean impersonated)
            throws Exception {
        return mvc.perform(request.header("Host", CLUB + ".example.test").with(jwt().jwt(j -> { j.subject(account).claim("clubId", CLUB).claim("locale", "ca");
            if (impersonated) { j.jti("grant-laura").claim("imp", true).claim("actorAccountId", "admin-account").claim("impersonatedMemberId", "laura"); } })
                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))));
    }
    Map<String, byte[]> unzip(byte[] zip) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) { entries.put(entry.getName(), input.readAllBytes()); }
        }
        return entries;
    }

    @Test @AuditCovers(AuditAction.MEMBER_DATA_EXPORTED)
    void T_14_17_R_14_13_theMembersPackageHoldsItsDataItsFilesAndAnnouncesItself() throws Exception {
        var accepted = json(member(body(post("/api/v1/me/data-export"), Map.of()), "laura-account", false), 202);
        String id = accepted.path("jobId").asText();
        assertThat(accepted.path("statusUrl").asText()).isEqualTo("/api/v1/exports/" + id);
        assertThat(json(member(get("/api/v1/exports/" + id), "laura-account", false), 200).path("status").asText()).isEqualTo("QUEUED");
        worker.poll();
        var job = json(member(get("/api/v1/exports/" + id), "laura-account", false), 200);
        assertThat(job.path("status").asText()).isEqualTo("READY");
        assertThat(job.path("kind").asText()).isEqualTo("MEMBER_DATA");
        assertThat(job.path("format").asText()).isEqualTo("ZIP");
        assertThat(job.path("expiresAt").asText()).isEqualTo(NOW.plus(Duration.ofDays(7)).toString());
        String url = job.path("downloadUrl").asText();
        assertThat(url).startsWith("/api/v1/exports/" + id + "/download");
        byte[] zip = member(get(url), "laura-account", false).andReturn().getResponse().getContentAsByteArray();
        var entries = unzip(zip);
        assertThat(entries).containsKeys("data.json", "resum.pdf", "fitxers/gossos/duna/documents/VACCINATION-cartilla-duna.pdf");
        assertThat(entries.get("fitxers/gossos/duna/documents/VACCINATION-cartilla-duna.pdf")).containsExactly(37, 80, 68, 70);
        JsonNode data = mapper.readTree(entries.get("data.json"));
        assertThat(data.path("schema").asText()).isEqualTo("memberData.v1");
        assertThat(data.path("account").path("email").asText()).isEqualTo("laura@example.test");
        assertThat(data.path("memberships")).hasSize(1);
        assertThat(data.path("member").path("firstName").asText()).isEqualTo("Fictional");
        assertThat(data.path("member").path("paymentMethod").path("iban").asText()).isEqualTo("···· 1332");
        assertThat(new String(entries.get("data.json"), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("ES9121", "Fictional Admin", "\"clubId\" : \"" + CLUB + "\", \"version\"");
        assertThat(data.path("consents")).hasSize(2);
        assertThat(data.path("dogs")).hasSize(2);
        assertThat(data.path("dogs").findValuesAsText("chip")).containsExactlyInAnyOrder("941000000200001", "941000000200002");
        assertThat(data.path("dogs").get(0).path("documents")).hasSize(1);
        assertThat(data.path("invoices")).hasSize(38);
        assertThat(data.path("invoices").get(0).path("collections")).hasSize(1);
        assertThat(data.path("auditEntries")).singleElement().satisfies(entry -> {
            assertThat(entry.path("action").asText()).isEqualTo("MEMBER_UPDATED");
            assertThat(entry.path("actorRole").asText()).isEqualTo("ADMIN");
            assertThat(entry.has("actorName")).isFalse();
        });
        assertThat(data.path("notifications")).singleElement().satisfies(notice -> assertThat(notice.path("title").asText()).isEqualTo("Hola Laura"));
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(entries.get("resum.pdf"))) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains("GOSSOS", "Les meves dades", "Pàgina 1 de");
        }
        // N-50 for the member, MEMBER_DATA_EXPORTED on the member, DataExported{kind: MEMBER_DATA}.
        assertThat(mongo.find(Query.query(Criteria.where("code").is("N-50").and("recipient.memberId").is("laura")), org.bson.Document.class, "notifications")).isNotEmpty();
        assertThat(documents("audit_entries", "action", "MEMBER_DATA_EXPORTED")).singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("memberId", "laura").containsEntry("entityId", id));
        assertThat(mongo.findOne(Query.query(Criteria.where("type").is("DataExported").and("aggregateId").is(id)), org.bson.Document.class, "domain_events")
                .get("payload", org.bson.Document.class)).containsEntry("kind", "MEMBER_DATA").containsEntry("jobId", id);
        // One per 24 hours; never by impersonation; nobody else reads it.
        assertThat(json(member(body(post("/api/v1/me/data-export"), Map.of()), "laura-account", false), 422).path("code").asText()).isEqualTo("DATA_EXPORT_TOO_SOON");
        assertThat(json(member(body(post("/api/v1/me/data-export"), Map.of()), "laura-account", true), 403).path("code").asText()).isEqualTo("IMPERSONATION_DENIED");
        assertThat(json(member(get("/api/v1/exports/" + id), "pau-account", false), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        clock.setInstant(NOW.plus(Duration.ofHours(24)));
        json(member(body(post("/api/v1/me/data-export"), Map.of()), "laura-account", false), 202);
    }

    @Test void T_14_17_R_14_13_anAdminPackagesAMemberOfItsClubWithOrWithoutDelivery() throws Exception {
        String id = json(admin(body(post("/api/v1/members/pau/data-export"), Map.of("deliverToMember", false))), 202).path("jobId").asText();
        worker.poll();
        var job = json(admin(get("/api/v1/exports/" + id)), 200);
        assertThat(job.path("status").asText()).isEqualTo("READY");
        var data = mapper.readTree(unzip(admin(get(job.path("downloadUrl").asText())).andReturn().getResponse().getContentAsByteArray()).get("data.json"));
        assertThat(data.path("member").path("lastName1").asText()).isEqualTo("Person pau");
        assertThat(data.path("invoices")).hasSize(1);
        assertThat(mongo.count(Query.query(Criteria.where("code").is("N-50")), "notifications")).isZero();
        assertThat(json(admin(body(post("/api/v1/members/pau/data-export"), Map.of("deliverToMember", true))), 422).path("code").asText()).isEqualTo("DATA_EXPORT_TOO_SOON");
        assertThat(json(call(body(post("/api/v1/members/pau/data-export"), Map.of("deliverToMember", true)), OTHER, "other-admin-account", "ADMIN"), 404)
                .path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(json(call(body(post("/api/v1/members/laura/data-export"), Map.of("deliverToMember", true)), CLUB, "pau-account", "MEMBER"), 403)
                .path("code").asText()).isEqualTo("FORBIDDEN");
    }
}
