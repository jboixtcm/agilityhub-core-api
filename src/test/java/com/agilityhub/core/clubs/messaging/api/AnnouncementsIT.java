package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.agilityhub.core.support.NotificationRows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * E7-T04 step 2 against the API (S11 R-11-13, T-11-18): «Enviar comunicat» with N-24 and with a `CUSTOM` template, recipients
 * by `memberIds` and by the filters of `GET /members` (the same semantics: the list's own answer is the oracle), the dry run that
 * counts and writes nothing, the real send with its batch, `AnnouncementSent`, the `ANNOUNCEMENT_SENT` audit and one N-24 per
 * member (`dedupKey {batchId}:{memberId}`) with each member's preferences and language, the `Idempotency-Key` replay with the
 * same `batchId`, and the refusals: another code (`TEMPLATE_NOT_SENDABLE`, 422), nobody (`NO_RECIPIENTS`, 422), an archived or
 * another club's template (404), both or neither kind of recipients (400), an undeclared filter (400), other roles (403).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AnnouncementsIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t04-ann-a", OTHER = "e7t04-ann-b", HOST = "ann-a.example.test", OTHER_HOST = "ann-b.example.test", ADMIN = "e7t04-ann-admin";
    static final List<String> DATA = List.of("members", "dogs", "message_templates", "notifications", "domain_events", "audit_entries", "announcements",
            "push_subscriptions", "idempotency_records", "memberships");
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts; @Autowired OutboxDispatcher outbox;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z"));
        club(CLUB, HOST); club(OTHER, OTHER_HOST); hosts.invalidate();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(Query.query(Criteria.where("_id").regex("^e7t04-ann-")), "accounts");
        account(ADMIN, "ca");
        // Fictional members: Laura (ca), Joan (es, no CLUB_NEWS e-mail nor push), Pere (en, a push device), Marc who left, Núria pending.
        member("laura", "Laura", "Serra", "ACTIVE", "ca", Map.of());
        member("joan", "Joan", "Puig", "ACTIVE", "es", Map.of("emailByCategory", Map.of("CLUB_NEWS", false), "pushClubNews", false));
        member("pere", "Pere", "Vidal", "ACTIVE", "en", Map.of());
        member("marc", "Marc", "Roca", "LEFT", "es", Map.of());
        member("nuria", "Núria", "Pons", "PENDING", "ca", Map.of());
        mongo.save(new Document("_id", "e7t04-ann-other").append("clubId", OTHER).append("status", "ACTIVE").append("firstName", "Other").append("lastName1", "Club")
                .append("bookingBlock", Map.of("active", false)).append("version", 0), "members");
        var keys = new PushSubscription.Keys(com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.p256dh(), com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.auth());
        mongo.insert(new PushSubscription("e7t04-ann-sub-pere", CLUB, "e7t04-ann-acc-pere", "https://push.example.test/e7t04-pere", null, keys, "iPhone · Safari", "UA",
                PushSubscription.Status.ACTIVE, 0, null, null, 0L, clock.instant(), "e7t04-ann-acc-pere", clock.instant(), "e7t04-ann-acc-pere"));
    }
    private void club(String clubId, String host) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, host));
        tree.set("modules", mapper.valueToTree(List.of(Module.values())));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }
    private void account(String id, String locale) {
        mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + id, locale, null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
    }
    private void member(String id, String first, String last, String status, String locale, Map<String, Object> preferences) {
        String account = "e7t04-ann-acc-" + id;
        account(account, locale);
        mongo.save(new Document("_id", "e7t04-ann-" + id).append("clubId", CLUB).append("accountId", account).append("firstName", first).append("lastName1", last)
                .append("gender", "OTHER").append("status", status).append("bookingBlock", new Document("active", false)).append("signup", new Document("locale", locale))
                .append("contactEmails", List.of(new Document("email", id + ".e7t04@example.test"))).append("phones", List.of(new Document("prefix", "+34").append("number", "600000001")))
                .append("notificationPreferences", preferences).append("memberNumber", Math.abs(id.hashCode() % 1000)).append("version", 0), "members");
        mongo.save(new Document("_id", "e7t04-ann-dog-" + id).append("clubId", CLUB).append("memberId", "e7t04-ann-" + id).append("name", "Gos " + first)
                .append("status", "ACTIVE").append("version", 0), "dogs");
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) { return as(request, role, CLUB); }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role, String clubId) {
        return request.header("Host", clubId.equals(CLUB) ? HOST : OTHER_HOST).contentType("application/json")
                .with(jwt().jwt(j -> j.subject(role.equals("ADMIN") ? ADMIN : "e7t04-ann-" + role).claim("clubId", clubId).claim("locale", "ca"))
                        .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    private JsonNode send(String templateId, Map<String, Object> recipients, boolean dryRun, String key, int status) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("recipients", recipients); body.put("dryRun", dryRun);
        return call(as(post("/api/v1/message-templates/" + templateId + "/send"), "ADMIN").header("Idempotency-Key", key).content(mapper.writeValueAsBytes(body)), status);
    }
    private void refused(String templateId, Map<String, Object> recipients, int status, ErrorCode code) throws Exception {
        for (boolean dryRun : new boolean[] {true, false}) {
            var body = send(templateId, recipients, dryRun, UUID.randomUUID().toString(), status);
            assertThat(body.path("code").asText()).as(templateId + " " + recipients + " dryRun=" + dryRun).isEqualTo(code.name());
        }
    }
    private String templateOf(String code) throws Exception {
        for (var item : call(as(get("/api/v1/message-templates"), "ADMIN"), 200).path("items")) { if (code.equals(item.path("code").asText())) { return item.path("id").asText(); } }
        throw new AssertionError("no " + code);
    }
    private long count(String collection, Criteria criteria) { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB)).addCriteria(criteria), collection); }
    private long writes() {
        return count("announcements", new Criteria()) + count("domain_events", Criteria.where("type").is("AnnouncementSent"))
                + count("audit_entries", Criteria.where("action").is("ANNOUNCEMENT_SENT")) + count("notifications", Criteria.where("code").is("N-24"));
    }
    private void dispatch() { for (int i = 0; i < 6; i++) { outbox.dispatch(); } }
    private List<String> listed(String... filters) throws Exception {
        var request = as(get("/api/v1/members"), "ADMIN").param("size", "1000");
        for (String filter : filters) { request.param("filter", filter); }
        var ids = new java.util.ArrayList<String>();
        call(request, 200).path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    @Test @AuditCovers(AuditAction.ANNOUNCEMENT_SENT)
    void T_11_18_n24ToTheMembersOfTheListFiltersWithTheirOwnPreferencesAuditedAndReplayedByItsKey() throws Exception {
        String n24 = templateOf("N-24");
        // The oracle: GET /members with the same filter (ACTIVE: Laura, Joan, Pere).
        var active = listed("status:eq:ACTIVE");
        assertThat(active).containsExactlyInAnyOrder("e7t04-ann-laura", "e7t04-ann-joan", "e7t04-ann-pere");
        // Dry run: «S'enviarà a 3 abonats», nothing written.
        var dry = send(n24, Map.of("filters", List.of("status:eq:ACTIVE")), true, UUID.randomUUID().toString(), 200);
        assertThat(dry.path("batchId").isNull()).isTrue(); assertThat(dry.path("recipientCount").asInt()).isEqualTo(3);
        assertThat(writes()).isZero();
        // No filter at all: what the list shows, any status (Núria is PENDING), but Marc, who left: R-11-02 never sends him club news.
        var everyone = listed().stream().filter(id -> !id.equals("e7t04-ann-marc")).toList();
        assertThat(everyone).contains("e7t04-ann-nuria").hasSize(4);
        assertThat(send(n24, Map.of("filters", List.of()), true, UUID.randomUUID().toString(), 200).path("recipientCount").asInt()).isEqualTo(everyone.size());

        // The real send.
        String key = UUID.randomUUID().toString();
        var sent = send(n24, Map.of("filters", List.of("status:eq:ACTIVE")), false, key, 202);
        String batchId = sent.path("batchId").asText();
        assertThat(batchId).isNotBlank(); assertThat(sent.path("recipientCount").asInt()).isEqualTo(3);
        var batch = mongo.findById(batchId, Document.class, "announcements");
        assertThat(batch.getString("clubId")).isEqualTo(CLUB); assertThat(batch.getString("templateId")).isEqualTo(n24);
        assertThat(batch.getList("filters", String.class)).containsExactly("status:eq:ACTIVE"); assertThat(batch.getString("selection")).isEqualTo("FILTERS");
        assertThat(batch.getList("memberIds", String.class)).containsExactlyInAnyOrderElementsOf(active);
        assertThat(batch.getInteger("recipientCount")).isEqualTo(3); assertThat(batch.getString("actorAccountId")).isEqualTo(ADMIN);
        var event = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("AnnouncementSent")), Document.class, "domain_events");
        assertThat(event.getString("aggregateType")).isEqualTo("Announcement"); assertThat(event.getString("aggregateId")).isEqualTo(batchId);
        assertThat(event.get("payload", Document.class).keySet()).containsExactlyInAnyOrder("templateId", "batchId", "recipientCount", "filters");
        assertThat(event.get("payload", Document.class)).containsEntry("templateId", n24).containsEntry("batchId", batchId).containsEntry("recipientCount", 3)
                .containsEntry("filters", List.of("status:eq:ACTIVE"));
        // ANNOUNCEMENT_SENT on the template, by the admin, with {batchId, recipientCount, filters} — only in this club.
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("ANNOUNCEMENT_SENT")), Document.class, "audit_entries");
        assertThat(audit.getString("entityType")).isEqualTo("MessageTemplate"); assertThat(audit.getString("entityId")).isEqualTo(n24);
        assertThat(audit.getString("actorAccountId")).isEqualTo(ADMIN); assertThat(audit.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(audit.getList("changes", Document.class)).extracting(c -> c.getString("path")).contains("batchId", "recipientCount", "filters");
        assertThat(audit.toJson()).contains(batchId, "status:eq:ACTIVE");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER).and("action").is("ANNOUNCEMENT_SENT")), "audit_entries")).isZero();
        // The same Idempotency-Key: the same batch, nothing new.
        var replay = send(n24, Map.of("filters", List.of("status:eq:ACTIVE")), false, key, 202);
        assertThat(replay.path("batchId").asText()).isEqualTo(batchId);
        assertThat(count("announcements", new Criteria())).isEqualTo(1); assertThat(count("domain_events", Criteria.where("type").is("AnnouncementSent"))).isEqualTo(1);
        assertThat(count("audit_entries", Criteria.where("action").is("ANNOUNCEMENT_SENT"))).isEqualTo(1);

        // The engine: one MEMBER N-24 per member, each with its own preferences and language.
        dispatch();
        var rows = NotificationRows.rows(mongo, CLUB, "N-24");
        assertThat(rows).extracting(r -> r.getString("memberId")).containsOnly("e7t04-ann-laura", "e7t04-ann-joan", "e7t04-ann-pere");
        assertThat(rows).allSatisfy(r -> { assertThat(r.getString("audience")).isEqualTo("MEMBER"); assertThat(r.getString("dedupKey")).isEqualTo(batchId + ":" + r.getString("memberId")); });
        assertThat(channels(rows, "e7t04-ann-laura")).containsExactlyInAnyOrder("APP:DELIVERED", "EMAIL:SENT", "PUSH:SKIPPED_NO_CONTACT");
        assertThat(channels(rows, "e7t04-ann-joan")).containsExactlyInAnyOrder("APP:DELIVERED", "EMAIL:SKIPPED_BY_PREFERENCE", "PUSH:SKIPPED_BY_PREFERENCE");
        assertThat(channels(rows, "e7t04-ann-pere")).containsExactlyInAnyOrder("APP:DELIVERED", "EMAIL:SENT", "PUSH:SENT");
        assertThat(locale(rows, "e7t04-ann-laura")).isEqualTo("ca"); assertThat(locale(rows, "e7t04-ann-joan")).isEqualTo("es");
        assertThat(locale(rows, "e7t04-ann-pere")).isEqualTo("en");
        // A second dispatch of the same event creates nothing (R-11-09).
        dispatch();
        assertThat(NotificationRows.notifications(mongo, CLUB, "N-24")).hasSize(3);
    }

    @Test void T_11_18_aCustomTemplateToASelectionWithItsOwnCategoryAndText() throws Exception {
        // A CUSTOM PERSONAL notice, «Comunicat del club», to an explicit selection with a member who left and an unknown id.
        var create = Map.of("category", "PERSONAL", "title", Map.of("ca", "Comunicat del club", "es", "Comunicado del club"),
                "body", Map.of("ca", "Hola [[member_first_name]], el dissabte el club fa una festa amb [[dog_name]].", "es", "Hola [[member_first_name]], el sábado hay fiesta con [[dog_name]]."),
                "icon", "flag", "color", "ACCENT", "matrix", matrix(true));
        String custom = call(as(post("/api/v1/message-templates"), "ADMIN").content(mapper.writeValueAsBytes(create)), 201).path("id").asText();
        var selection = Map.<String, Object>of("memberIds", List.of("e7t04-ann-joan", "e7t04-ann-marc", "e7t04-ann-nobody", "e7t04-ann-other", "e7t04-ann-joan"));
        assertThat(send(custom, selection, true, UUID.randomUUID().toString(), 200).path("recipientCount").asInt()).isEqualTo(1);
        var sent = send(custom, selection, false, UUID.randomUUID().toString(), 202);
        assertThat(sent.path("recipientCount").asInt()).isEqualTo(1);
        var batch = mongo.findById(sent.path("batchId").asText(), Document.class, "announcements");
        assertThat(batch.getString("selection")).isEqualTo("MEMBERS"); assertThat(batch.getList("filters", String.class)).isEmpty();
        assertThat(batch.getList("memberIds", String.class)).containsExactly("e7t04-ann-joan");
        dispatch();
        var rows = NotificationRows.rows(mongo, CLUB, "N-24");
        assertThat(rows).extracting(r -> r.getString("memberId")).containsOnly("e7t04-ann-joan");
        // Joan turned CLUB_NEWS e-mail off, not PERSONAL: the PERSONAL notice e-mails him; its title and body are the CUSTOM ones in Spanish.
        assertThat(channels(rows, "e7t04-ann-joan")).containsExactlyInAnyOrder("APP:DELIVERED", "EMAIL:SENT", "PUSH:SKIPPED_NO_CONTACT");
        var notification = NotificationRows.notifications(mongo, CLUB, "N-24").getFirst();
        assertThat(notification.getString("templateId")).isEqualTo(custom); assertThat(notification.getString("category")).isEqualTo("PERSONAL");
        assertThat(notification.getString("title")).isEqualTo("Comunicado del club");
        assertThat(notification.getString("body")).isEqualTo("Hola Joan, el sábado hay fiesta con Gos Joan.");
    }

    @Test void T_11_18_theRefusals() throws Exception {
        String n24 = templateOf("N-24"), n08a = templateOf("N-08a");
        var active = Map.<String, Object>of("filters", List.of("status:eq:ACTIVE"));
        // Only N-24 or a CUSTOM template (422, CATALEG_ERRORS rule 0 although S11 §6 writes 409).
        refused(n08a, active, 422, ErrorCode.TEMPLATE_NOT_SENDABLE);
        // Nobody: a filter matching no member, an empty selection, only a member who left.
        refused(n24, Map.of("filters", List.of("fullName:contains:Zzzz")), 422, ErrorCode.NO_RECIPIENTS);
        refused(n24, Map.of("memberIds", List.of()), 422, ErrorCode.NO_RECIPIENTS);
        refused(n24, Map.of("filters", List.of("status:eq:LEFT")), 422, ErrorCode.NO_RECIPIENTS);
        // Both kinds of recipients, or neither: 400; an undeclared filter: the list's INVALID_FILTER.
        refused(n24, Map.of("memberIds", List.of("e7t04-ann-laura"), "filters", List.of("status:eq:ACTIVE")), 400, ErrorCode.VALIDATION_ERROR);
        refused(n24, Map.of(), 400, ErrorCode.VALIDATION_ERROR);
        refused(n24, Map.of("filters", List.of("shoeSize:eq:42")), 400, ErrorCode.INVALID_FILTER);
        // A disabled N-24 is not sendable; an archived CUSTOM template or another club's template is not found.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(n24)), new org.springframework.data.mongodb.core.query.Update().set("enabled", false).set("status", "DISABLED"), "message_templates");
        refused(n24, active, 422, ErrorCode.TEMPLATE_NOT_SENDABLE);
        var create = Map.of("category", "CLUB_NEWS", "title", Map.of("ca", "Festa"), "body", Map.of("ca", "Hola [[member_first_name]]"), "icon", "flag", "color", "ACCENT",
                "matrix", matrix(false));
        String custom = call(as(post("/api/v1/message-templates"), "ADMIN").content(mapper.writeValueAsBytes(create)), 201).path("id").asText();
        call(as(delete("/api/v1/message-templates/" + custom), "ADMIN"), 204);
        refused(custom, active, 404, ErrorCode.NOT_FOUND);
        var other = call(as(post("/api/v1/message-templates"), "ADMIN", OTHER).content(mapper.writeValueAsBytes(create)), 201).path("id").asText();
        refused(other, active, 404, ErrorCode.NOT_FOUND);
        // Members and instructors never send (403); nothing was written by any refusal.
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            var body = Map.of("recipients", active, "dryRun", false);
            assertThat(call(as(post("/api/v1/message-templates/" + n24 + "/send"), role).header("Idempotency-Key", UUID.randomUUID().toString())
                    .content(mapper.writeValueAsBytes(body)), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        }
        assertThat(writes()).isZero();
    }

    /** A CUSTOM template's matrix: the members' row (APP, and EMAIL when asked); staff never receive a CUSTOM notice (S11 §13-6). */
    private static Map<String, Object> matrix(boolean email) {
        var off = Map.of("APP", false, "EMAIL", false, "SMS", false);
        return Map.of("MEMBER", Map.of("APP", true, "EMAIL", email, "SMS", false), "INSTRUCTORS", off, "ADMINS", off);
    }
    private static List<String> channels(List<Document> rows, String memberId) {
        return rows.stream().filter(r -> memberId.equals(r.getString("memberId"))).map(r -> r.getString("channel") + ":" + r.getString("status")).toList();
    }
    private static String locale(List<Document> rows, String memberId) {
        return rows.stream().filter(r -> memberId.equals(r.getString("memberId"))).map(r -> r.getString("locale")).findFirst().orElseThrow();
    }
}
