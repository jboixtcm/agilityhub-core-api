package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * T-11-26 and T-11-29 (R-11-10), the club's notification log `GET /notifications` (ADMIN): the universal list filters by
 * `code`, `category`, `channel` and `status` of any delivery, `memberId` and `createdAt`, sorted `createdAt desc` by default,
 * with `fields`; an undeclared field is `400 INVALID_FILTER`; `filter-values` labels the values; the detail shows the frozen
 * texts and every delivery with the provider's reference and last error; the export (S14 R-14-12) takes the same list;
 * club B never sees club A's rows (404 on its ids); MEMBER, INSTRUCTOR and the impersonation token → 403.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NotificationLogIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t03-log-a", OTHER = "e7t03-log-b", HOST = "log-a.example.test", OTHER_HOST = "log-b.example.test";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    Instant now;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z")); now = clock.instant();
        for (String id : List.of(CLUB, OTHER)) {
            mongo.remove(Query.query(Criteria.where("_id").is(id)), Club.class);
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id.equals(CLUB) ? HOST : OTHER_HOST));
            tree.set("modules", mapper.valueToTree(List.of(Module.values())));
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
            mongo.remove(Query.query(Criteria.where("clubId").is(id)), "notifications"); mongo.remove(Query.query(Criteria.where("clubId").is(id)), "members");
            mongo.remove(Query.query(Criteria.where("clubId").is(id)), "export_jobs");
        }
        hosts.invalidate();
        mongo.save(new Document("_id", "log-m-laura").append("clubId", CLUB).append("status", "ACTIVE").append("firstName", "Laura").append("lastName1", "Serra")
                .append("bookingBlock", Map.of("active", false)).append("version", 0L), "members");
        var failed = new Notification.Delivery(NotificationChannel.SMS, "+34600000001", DeliveryStatus.FAILED, 5, null, "SM-exemple", "Twilio 21211: invalid number",
                now.minusSeconds(3000), null, now.minusSeconds(100));
        mongo.insert(row("log-n08a", CLUB, "N-08a", "log-m-laura", now.minusSeconds(60), new Notification.Delivery(NotificationChannel.APP, "log-laura",
                DeliveryStatus.DELIVERED, 0, null, null, null, null, now.minusSeconds(60), null), failed));
        mongo.insert(row("log-n04", CLUB, "N-04", "log-m-laura", now.minusSeconds(3600), new Notification.Delivery(NotificationChannel.APP, "log-laura",
                DeliveryStatus.DELIVERED, 0, null, null, null, null, now.minusSeconds(3600), null)));
        mongo.insert(row("log-n38", CLUB, "N-38", "log-m-marc", now.minusSeconds(30), new Notification.Delivery(NotificationChannel.EMAIL, "marc@example.test",
                DeliveryStatus.SKIPPED_BY_PREFERENCE, 0, null, null, null, null, null, null)));
        // A row written before E7-T02 (a helper row that does not tell its audience).
        mongo.insert(new Notification("log-legacy", CLUB, "log-laura", "N-08a", "APP", Notification.Status.SENT, null, now.minusSeconds(7200), null, null, "ca",
                now.minusSeconds(7200), null));
        mongo.insert(row("log-other", OTHER, "N-08a", "log-m-other", now.minusSeconds(10), new Notification.Delivery(NotificationChannel.APP, "log-other",
                DeliveryStatus.DELIVERED, 0, null, null, null, null, null, null)));
    }
    static Notification row(String id, String club, String code, String member, Instant at, Notification.Delivery... deliveries) {
        var n = NotificationFeedIT.notice(id, club, "log-account-" + member, code, NotificationAudience.MEMBER, at,
                code.equals("N-08a") ? new Notification.Action(NotificationActionType.CHANGE_CLASS, Map.of("dogId", "log-duna")) : null, deliveries);
        return new Notification(n.id(), n.clubId(), n.code(), n.category(), n.templateId(), n.templateVersion(), n.eventId(), n.eventType(), n.dedupKey(), n.audience(),
                new Notification.Recipient(n.recipient().accountId(), member, null, null, "Laura Serra"), n.locale(),
                new Notification.Subject("log-duna", null, "log-class", null, null, null, null, null, member), n.icon(), n.color(), n.title(), n.body(),
                code.equals("N-08a") ? "Club: classe anul·lada" : null, n.action(), n.deliveries(), null, at, null, null, null, null, null, null, null, null);
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role, String club) {
        return request.header("Host", club.equals(CLUB) ? HOST : OTHER_HOST).with(jwt().jwt(j -> j.subject("log-" + role).claim("clubId", club))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    private List<String> ids(String... filters) throws Exception {
        var request = get("/api/v1/notifications");
        for (String filter : filters) { request.param("filter", filter); }
        var ids = new ArrayList<String>(); call(as(request, "ADMIN", CLUB), 200).path("items").forEach(i -> ids.add(i.path("id").asText()));
        return ids;
    }

    @Test void T_11_26_theLogFiltersByCodeChannelStatusMemberAndDateNewestFirst() throws Exception {
        var page = call(as(get("/api/v1/notifications"), "ADMIN", CLUB), 200);
        assertThat(page.path("items").findValuesAsText("id")).containsExactly("log-n38", "log-n08a", "log-n04", "log-legacy");
        assertThat(page.path("totalItems").asLong()).isEqualTo(4);
        var first = page.path("items").get(1);
        assertThat(first.path("code").asText()).isEqualTo("N-08a"); assertThat(first.path("category").asText()).isEqualTo("CLUB_CHANGES");
        assertThat(first.path("audience").asText()).isEqualTo("MEMBER"); assertThat(first.path("readAt").isNull()).isTrue();
        assertThat(first.path("recipient")).isEqualTo(mapper.readTree("{\"displayName\":\"Laura Serra\",\"memberId\":\"log-m-laura\",\"email\":null}"));
        assertThat(first.path("channels")).isEqualTo(mapper.readTree("[{\"channel\":\"APP\",\"status\":\"DELIVERED\"},{\"channel\":\"SMS\",\"status\":\"FAILED\"}]"));
        assertThat(page.path("items").get(3).path("audience").isNull()).isTrue(); // the row that does not tell its audience
        assertThat(ids("code:eq:N-08a")).containsExactly("log-n08a", "log-legacy");
        assertThat(ids("channel:eq:SMS")).containsExactly("log-n08a");
        assertThat(ids("status:eq:FAILED")).containsExactly("log-n08a");
        assertThat(ids("status:in:SKIPPED_BY_PREFERENCE,SKIPPED_NO_CONTACT")).containsExactly("log-n38");
        assertThat(ids("memberId:eq:log-m-laura")).containsExactly("log-n08a", "log-n04");
        assertThat(ids("category:eq:PERSONAL")).containsExactly("log-n38");
        assertThat(ids("createdAt:between:" + now.minusSeconds(3700) + "," + now.minusSeconds(50))).containsExactly("log-n08a", "log-n04");
        assertThat(call(as(get("/api/v1/notifications").param("sort", "createdAt,asc"), "ADMIN", CLUB), 200).path("items").findValuesAsText("id"))
                .containsExactly("log-legacy", "log-n04", "log-n08a", "log-n38");
        // fields: the row id and the requested keys only.
        var sparse = call(as(get("/api/v1/notifications").param("fields", "code,channels"), "ADMIN", CLUB), 200).path("items").get(0);
        assertThat(sparse.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "code", "channels");
        // An undeclared field, operator, sort or size is 400 INVALID_FILTER.
        for (var bad : List.of(Map.entry("filter", "title:eq:x"), Map.entry("filter", "code:gt:N-01"), Map.entry("sort", "code,asc"), Map.entry("size", "7"),
                Map.entry("fields", "body"))) {
            assertThat(call(as(get("/api/v1/notifications").param(bad.getKey(), bad.getValue()), "ADMIN", CLUB), 400).path("code").asText()).isEqualTo("INVALID_FILTER");
        }
        // filter-values: labelled in the reader's language, the member's name for memberId.
        var statuses = call(as(get("/api/v1/notifications/filter-values").param("field", "status"), "ADMIN", CLUB), 200);
        assertThat(statuses.path("values").findValuesAsText("label")).contains("Lliurat", "Error", "No enviat (preferència)");
        var members = call(as(get("/api/v1/notifications/filter-values").param("field", "memberId").param("filter", "code:eq:N-08a"), "ADMIN", CLUB), 200);
        assertThat(members.path("values")).singleElement().satisfies(v -> { assertThat(v.path("value").asText()).isEqualTo("log-m-laura");
            assertThat(v.path("label").asText()).isEqualTo("Laura Serra"); assertThat(v.path("count").asLong()).isEqualTo(1); });
        call(as(get("/api/v1/notifications/filter-values").param("field", "title"), "ADMIN", CLUB), 400);
        assertThat(call(as(get("/api/v1/notifications/filter-values").param("field", "category"), "ADMIN", CLUB), 200).path("values").findValuesAsText("label"))
                .containsExactlyInAnyOrder("Canvis en reserves", "Comunicats individuals", "Operativa");
        assertThat(call(as(get("/api/v1/notifications/filter-values").param("field", "channel"), "ADMIN", CLUB), 200).path("values").findValuesAsText("label"))
                .containsExactlyInAnyOrder("App", "SMS", "Correu");
        assertThat(call(as(get("/api/v1/notifications/filter-values").param("field", "code"), "ADMIN", CLUB), 200).path("values").findValuesAsText("label"))
                .containsExactlyInAnyOrder("N-08a", "N-04", "N-38");
        System.out.println("E7-T03 log filter channel:eq:SMS → " + ids("channel:eq:SMS") + "; status:eq:FAILED → " + ids("status:eq:FAILED")
                + "; memberId:eq:log-m-laura → " + ids("memberId:eq:log-m-laura") + "; code:eq:N-08a → " + ids("code:eq:N-08a"));
    }

    @Test void T_11_26_theDetailShowsTheFrozenTextsAndEveryDeliveryWithTheProviderData() throws Exception {
        var detail = call(as(get("/api/v1/notifications/log-n08a"), "ADMIN", CLUB), 200);
        assertThat(detail.path("title").asText()).isEqualTo("Títol log-n08a"); assertThat(detail.path("body").asText()).isEqualTo("Cos log-n08a");
        assertThat(detail.path("smsBody").asText()).isEqualTo("Club: classe anul·lada"); assertThat(detail.path("locale").asText()).isEqualTo("ca");
        assertThat(detail.path("templateId").asText()).isEqualTo("tpl-N-08a"); assertThat(detail.path("eventType").asText()).isEqualTo("Example");
        assertThat(detail.at("/subject/dogId").asText()).isEqualTo("log-duna"); assertThat(detail.at("/subject/bookingId").isNull()).isTrue();
        var sms = detail.path("deliveries").get(1);
        assertThat(sms.path("channel").asText()).isEqualTo("SMS"); assertThat(sms.path("status").asText()).isEqualTo("FAILED");
        assertThat(sms.path("attempts").asInt()).isEqualTo(5); assertThat(sms.path("providerRef").asText()).isEqualTo("SM-exemple");
        assertThat(sms.path("lastError").asText()).isEqualTo("Twilio 21211: invalid number"); assertThat(sms.path("failedAt").asText()).isEqualTo(now.minusSeconds(100).toString());
        assertThat(detail.path("deliveries").get(0).path("target").asText()).isEqualTo("log-laura");
        var legacy = call(as(get("/api/v1/notifications/log-legacy"), "ADMIN", CLUB), 200);
        assertThat(legacy.path("audience").isNull()).isTrue(); assertThat(legacy.path("title").asText()).isEmpty();
        assertThat(legacy.path("deliveries")).singleElement().satisfies(d -> assertThat(d.path("status").asText()).isEqualTo("SENT"));
    }

    @Test void T_11_26_theExportTakesTheSameListAndItsFilters() throws Exception {
        var response = mvc.perform(as(get("/api/v1/notifications/export").param("format", "xlsx").param("filter", "memberId:eq:log-m-laura")
                .param("columns", "createdAt,code,recipient,channels"), "ADMIN", CLUB)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("Content-Disposition")).contains(".xlsx");
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            var cells = new ArrayList<String>();
            sheet.forEach(row -> row.forEach(cell -> cells.add(cell.toString())));
            assertThat(String.join(" | ", cells)).contains("Avís", "Destinatari", "Canals", "N-08a", "N-04", "Laura Serra").doesNotContain("N-38", "log-other");
        }
        assertThat(call(as(get("/api/v1/notifications/export").param("format", "xlsx").param("columns", "body"), "ADMIN", CLUB), 400).path("code").asText())
                .isEqualTo("INVALID_FILTER");
    }

    /**
     * E7-T03 round 2, review #5 (S14 R-14-12): the export's cells are the reader's, never the list's objects. «Destinatari» is the
     * recipient's name — an applicant's address when the row has none — and never an internal id; «Canals» is each channel with
     * its delivery status, translated from the export's own keys (`export.value.notificationChannel.*`,
     * `export.value.deliveryStatus.*`), in XLSX and in PDF and in the reader's language. Before the fix the cells were the
     * flattened objects: «Laura Serra; log-m-laura» and «APP; DELIVERED; SMS; FAILED».
     */
    @Test void T_11_26_R_14_12_theExportCellsAreTheRecipientsNameAndTheTranslatedChannelsWithTheirStatus() throws Exception {
        // An applicant's row (N-03 of a rejected signup): no name, no member; its address is the only recipient data.
        var applicant = NotificationFeedIT.notice("log-n03", CLUB, null, "N-03", NotificationAudience.APPLICANT, now.minusSeconds(120),
                null, new Notification.Delivery(NotificationChannel.EMAIL, "applicant@example.test", DeliveryStatus.SENT, 1, null, "SG-1", null, now.minusSeconds(120), null, null));
        mongo.insert(new Notification(applicant.id(), CLUB, "N-03", applicant.category(), applicant.templateId(), applicant.templateVersion(), applicant.eventId(),
                applicant.eventType(), applicant.dedupKey(), NotificationAudience.APPLICANT, new Notification.Recipient(null, null, null, "applicant@example.test", ""),
                "ca", applicant.subject(), applicant.icon(), applicant.color(), applicant.title(), applicant.body(), null, null, applicant.deliveries(), null, now.minusSeconds(120),
                null, null, null, null, null, null, null, null));
        var xlsx = mvc.perform(as(get("/api/v1/notifications/export").param("filter", "code:in:N-08a,N-03,N-04").param("columns", "createdAt,code,recipient,channels")
                .param("format", "xlsx"), "ADMIN", CLUB)).andReturn().getResponse();
        assertThat(xlsx.getStatus()).as(xlsx.getContentAsString()).isEqualTo(200);
        var rows = new ArrayList<List<String>>();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
            workbook.getSheetAt(0).forEach(row -> { var cells = new ArrayList<String>(); row.forEach(cell -> cells.add(cell.toString())); rows.add(cells.subList(1, cells.size())); });
        }
        // The row written before E7-T02 does not tell its recipient's name: its cell is empty, never the account id it stores.
        assertThat(rows).containsExactly(List.of("Avís", "Destinatari", "Canals"), List.of("N-08a", "Laura Serra", "App (Lliurat); SMS (Error)"),
                List.of("N-03", "applicant@example.test", "Correu (Enviat)"), List.of("N-04", "Laura Serra", "App (Lliurat)"), List.of("N-08a", "", "App (Enviat)"));
        var pdf = mvc.perform(as(get("/api/v1/notifications/export").param("filter", "code:in:N-08a,N-03,N-04").param("columns", "createdAt,code,recipient,channels")
                .param("format", "pdf"), "ADMIN", CLUB)).andReturn().getResponse();
        assertThat(pdf.getStatus()).isEqualTo(200);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf.getContentAsByteArray())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains("Destinatari: Laura Serra", "Canals: App (Lliurat); SMS (Error)", "Destinatari: applicant@example.test", "Canals: Correu (Enviat)",
                    "Canals: App (Lliurat)").doesNotContain("log-m-laura", "DELIVERED", "APP;");
        }
        // The reader's language (es).
        var spanish = mvc.perform(get("/api/v1/notifications/export").param("filter", "code:eq:N-08a").param("columns", "code,channels").param("format", "xlsx")
                .header("Host", HOST).with(jwt().jwt(j -> j.subject("log-ADMIN").claim("clubId", CLUB).claim("locale", "es")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andReturn().getResponse();
        assertThat(spanish.getStatus()).isEqualTo(200);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(spanish.getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).toString()).isEqualTo("App (Entregado); SMS (Error)");
        }
    }

    @Test void T_11_28_T_11_29_onlyTheClubsAdminsReadTheClubsLog() throws Exception {
        assertThat(call(as(get("/api/v1/notifications"), "ADMIN", OTHER), 200).path("items").findValuesAsText("id")).containsExactly("log-other");
        call(as(get("/api/v1/notifications/log-n08a"), "ADMIN", OTHER), 404);
        call(as(get("/api/v1/notifications/log-other"), "ADMIN", CLUB), 404);
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            call(as(get("/api/v1/notifications"), role, CLUB), 403); call(as(get("/api/v1/notifications/log-n08a"), role, CLUB), 403);
            call(as(get("/api/v1/notifications/filter-values").param("field", "code"), role, CLUB), 403);
        }
    }
}
