package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * S10 WP-10-C (E6-T03) over a fictional club: Laura (Duna C, Rock inactive) and Joan (Toby) share a family group, three
 * active instructors (Estel ca, Marc es, Núria en) and a former one, an admin. Tasks with N-20/N-21 (R-10-10), attachments
 * on the signed local storage (R-10-11), observations (R-10-12), D14 and its read marks (R-10-13), the S03 consumers, the
 * port of the sheet and the card, roles, tenant and impersonation (T-10-22) and TASKS off (T-10-33).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class FollowupIT extends AbstractIntegrationTest {
    static final String CLUB = "s10f-a", OTHER = "s10f-b", HOST = "s10f-a.example.test", OTHER_HOST = "s10f-b.example.test";
    static final Instant NOW = Instant.parse("2026-08-12T14:00:00Z");
    static final List<String> DATA = List.of("tasks", "attachments", "attachment_uploads", "attachment_write_locks", "followup_items", "followup_read_marks", "members", "dogs",
            "family_groups", "memberships", "levels", "instructors", "parameters", "domain_events", "notifications", "audit_entries", "idempotency_records", "bookings");
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts; @Autowired OutboxDispatcher dispatcher; @Autowired TransactionTemplate tx; @Autowired EventPublisher events;
    @Autowired EmailSender email; @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("followup.MemberNoteChanged") DomainEventHandler<com.agilityhub.core.clubs.followup.domain.CensusForeignEvent> noteConsumer;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("followup.DogTransferred") DomainEventHandler<com.agilityhub.core.clubs.followup.domain.CensusForeignEvent> transferConsumer;

    @BeforeEach void fixtures() {
        clock.setInstant(NOW); ((FakeEmailSender) email).clear();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(Query.query(Criteria.where("_id").regex("^s10f-")), "accounts");
        club(CLUB, HOST, List.of(Module.values())); club(OTHER, OTHER_HOST, List.of(Module.values())); hosts.invalidate();
        mongo.save(new Document("_id", "s10f-lv-C").append("clubId", CLUB).append("code", "C").append("nameKeys", List.of("c"))
                .append("name", new Document("values", new Document("ca", "C").append("es", "C").append("en", "C")).append("defaultLocale", "ca"))
                .append("active", true).append("order", 1).append("capacity", 5).append("grantsFreeTraining", false).append("version", 0), "levels");
        parameter("levels.enabled", true);
        person("laura", "MEMBER", "Laura", "FEMALE", "ca"); person("joan", "MEMBER", "Joan", "MALE", "es"); person("admin", "ADMIN", "Admin", "FEMALE", "ca");
        person("estel", "INSTRUCTOR", "Estel", "FEMALE", "ca"); person("marc", "INSTRUCTOR", "Marc", "MALE", "es"); person("nuria", "INSTRUCTOR", "Núria", "FEMALE", "en");
        dog("s10f-d-duna", "s10f-m-laura", "Duna", "ACTIVE"); dog("s10f-d-rock", "s10f-m-laura", "Rock", "INACTIVE"); dog("s10f-d-toby", "s10f-m-joan", "Toby", "ACTIVE");
        mongo.save(new Document("_id", "s10f-d-other").append("clubId", OTHER).append("memberId", "s10f-m-other").append("name", "Aliè").append("status", "ACTIVE").append("version", 0), "dogs");
        for (var instructor : List.of(List.of("estel", "Estel", true), List.of("marc", "Marc", true), List.of("nuria", "Núria", true), List.of("old", "Vell", false))) {
            mongo.save(new Document("_id", "s10f-i-" + instructor.get(0)).append("clubId", CLUB).append("memberId", "s10f-m-" + instructor.get(0))
                    .append("shortName", instructor.get(1)).append("color", "#123456").append("active", instructor.get(2)).append("version", 0), "instructors");
        }
        mongo.save(new Document("_id", "s10f-group").append("clubId", CLUB).append("holderMemberId", "s10f-m-laura").append("memberIds", List.of("s10f-m-laura", "s10f-m-joan"))
                .append("status", "ACTIVE").append("version", 0), "family_groups");
        for (String m : List.of("s10f-m-laura", "s10f-m-joan")) { mongo.updateFirst(Query.query(Criteria.where("_id").is(m)), new Update().set("familyGroupId", "s10f-group"), "members"); }
    }
    void club(String id, String host, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(id)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, host));
        tree.set("modules", mapper.valueToTree(modules)); tree.put("timeZone", "Europe/Madrid"); tree.set("locales", mapper.valueToTree(List.of("ca", "es", "en")));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
    }
    void modules(Module... modules) { club(CLUB, HOST, List.of(modules)); }
    void parameter(String key, Object value) {
        mongo.getCollection("parameters").deleteMany(new Document("clubId", CLUB).append("key", key));
        mongo.insert(new Parameter(UUID.randomUUID().toString(), CLUB, key, value, "unknown", "club", null, List.of(), 0L, clock.instant()));
        configs.invalidate(CLUB);
    }
    /** An account with its membership and its census member (`s10f-<id>`, `s10f-m-<id>`), fictional data only. */
    void person(String id, String role, String firstName, String gender, String locale) {
        String account = "s10f-" + id, member = "s10f-m-" + id;
        mongo.save(new com.agilityhub.core.identity.persistence.Account(account, account + "@example.test", firstName + " Example", locale, null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(account, account, CLUB, member, Set.of(com.agilityhub.core.identity.domain.Role.valueOf(role)),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.valueOf(role)));
        mongo.save(new Document("_id", member).append("clubId", CLUB).append("accountId", account).append("firstName", firstName).append("lastName1", "Example")
                .append("gender", gender).append("memberNumber", Math.abs(id.hashCode() % 10000)).append("status", "ACTIVE").append("bookingBlock", new Document("active", false))
                .append("signup", new Document("locale", locale)).append("contactEmails", List.of(new Document("email", account + "@example.test"))).append("version", 0), "members");
    }
    void dog(String id, String memberId, String name, String status) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", memberId).append("name", name).append("sex", "FEMALE").append("status", status)
                .append("levelId", "s10f-lv-C").append("version", 0), "dogs");
    }
    RequestPostProcessor as(String id) {
        var membership = mongo.findOne(Query.query(Criteria.where("_id").is("s10f-" + id)), Document.class, "memberships");
        String member = membership.getString("memberId"), role = membership.getList("roles", String.class).getFirst();
        return jwt().jwt(j -> j.subject("s10f-" + id).claim("clubId", CLUB).claim("memberId", member).claim("name", id + " Example")).authorities(() -> "ROLE_" + role);
    }
    RequestPostProcessor impersonating(String memberId) {
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var tenant = TenantContext.open(CLUB)) { issued = impersonations.create("s10f-admin", memberId, "Fictional S10 support"); }
        return jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER");
    }
    JsonNode call(HttpMethod method, String path, Object body, RequestPostProcessor auth, int expected) throws Exception { return call(method, path, body, auth, expected, null); }
    JsonNode call(HttpMethod method, String path, Object body, RequestPostProcessor auth, int expected, String key) throws Exception {
        MockHttpServletRequestBuilder request = request(method, path.startsWith("/api/") ? path : "/api/v1" + path).header("Host", HOST);
        if (auth != null) { request.with(auth); }
        if (body != null) { request.contentType("application/json").content(mapper.writeValueAsBytes(body)); }
        if (key != null) { request.header("Idempotency-Key", key); }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(method + " " + path + " " + response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsByteArray().length == 0 || response.getContentType() == null || !response.getContentType().contains("json") ? mapper.nullNode()
                : mapper.readTree(response.getContentAsString());
    }
    String key() { return UUID.randomUUID().toString(); }
    String code(JsonNode error) { return error.path("code").asText(); }
    JsonNode createTask(String by, String dogId, String text, List<String> attachments, int expected) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("dogId", dogId); body.put("text", text); if (attachments != null) { body.put("attachmentIds", attachments); }
        return call(HttpMethod.POST, "/tasks", body, as(by), expected, key());
    }
    /** A signed upload through the local storage: the grant, then the PUT of the bytes; returns the file key. */
    String upload(RequestPostProcessor auth, String purpose, String name, String type, byte[] data) throws Exception {
        var grant = call(HttpMethod.POST, "/attachments/upload-url", Map.of("purpose", purpose, "fileName", name, "mimeType", type, "sizeBytes", data.length), auth, 201);
        var put = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(grant.path("uploadUrl").asText()).header("Host", HOST).contentType(type).content(data))
                .andReturn().getResponse();
        assertThat(put.getStatus()).as(put.getContentAsString()).isEqualTo(204);
        return grant.path("fileKey").asText();
    }
    void dispatch() { for (int i = 0; i < 6; i++) { dispatcher.dispatch(); } }
    long count(String collection, Criteria criteria) { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB)).addCriteria(criteria), collection); }
    List<Document> eventsOf(String type) { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type)), Document.class, "domain_events"); }
    List<Document> notifications(String code) {
        return com.agilityhub.core.support.NotificationRows.find(mongo,Criteria.where("clubId").is(CLUB).and("code").is(code)).stream()
                .sorted(java.util.Comparator.comparing((Document n) -> String.valueOf(n.getString("accountId"))).thenComparing(n -> n.getString("channel"))).toList();
    }
    Document task(String id) { return mongo.findById(id, Document.class, "tasks"); }
    Document row(String taskId) { return mongo.findById(com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository.taskRowId(taskId), Document.class, "followup_items"); }
    void publishCensus(String type, String dogId, Map<String, Object> payload) {
        try (var tenant = TenantContext.open(CLUB)) {
            tx.executeWithoutResult(status -> events.publish(new CensusEvent(type, CLUB, "Dog", dogId, clock.instant(), payload, null, null, DomainEvent.Origin.SYSTEM)));
        }
    }
    JsonNode followup(String who, String... params) throws Exception {
        var request = request(HttpMethod.GET, "/api/v1/followup").header("Host", HOST).with(as(who));
        for (int i = 0; i < params.length; i += 2) { request.param(params[i], params[i + 1]); }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }
    int unread(String who) throws Exception { return call(HttpMethod.GET, "/followup/unread-count", null, as(who), 200).path("count").asInt(); }

    // ---------------------------------------------------------------- tasks (R-10-10)

    @Test void T_10_15_aTaskIsCreatedEditedCompletedReopenedAndDeletedWithItsEventsAndNotifications() throws Exception {
        String text = "Practiqueu el balancí amb calma: sessions curtes de 5 minuts, i sempre acabant amb un èxit.\nDemà ho mirem a classe.";
        String idempotency = key();
        var body = Map.of("dogId", "s10f-d-duna", "text", text);
        var created = call(HttpMethod.POST, "/tasks", body, as("estel"), 201, idempotency);
        String id = created.path("id").asText();
        assertThat(created.path("state").asText()).isEqualTo("PENDING"); assertThat(created.path("text").asText()).isEqualTo(text);
        assertThat(created.path("createdBy").path("role").asText()).isEqualTo("INSTRUCTOR"); assertThat(created.path("createdBy").path("displayName").asText()).isEqualTo("Estel");
        assertThat(created.path("createdBy").path("accountId").asText()).isEqualTo("s10f-estel");
        assertThat(created.path("doneBy").isNull()).isTrue(); assertThat(created.path("attachments").isEmpty()).isTrue(); assertThat(created.path("version").asLong()).isZero();
        // T-10-25 (sequential double click): the same key replays the same 201, one task.
        assertThat(call(HttpMethod.POST, "/tasks", body, as("estel"), 201, idempotency)).isEqualTo(created);
        assertThat(count("tasks", Criteria.where("dogId").is("s10f-d-duna"))).isEqualTo(1);
        assertThat(task(id).getString("memberId")).isEqualTo("s10f-m-laura");
        var taskCreated = eventsOf("TaskCreated");
        // Round 2 (E64): the event freezes the owner and the excerpt of the creation, which N-20 sends.
        assertThat(taskCreated).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).isEqualTo(new Document("taskId", id).append("dogId", "s10f-d-duna")
                .append("memberId", "s10f-m-laura").append("textExcerpt", com.agilityhub.core.clubs.followup.domain.FollowupRules.excerpt(text))
                .append("by", new Document("accountId", "s10f-estel").append("role", "INSTRUCTOR"))));
        var d14 = row(id);
        assertThat(d14.getString("kind")).isEqualTo("TASK"); assertThat(d14.getString("authorAccountId")).isEqualTo("s10f-estel");
        assertThat(d14.getString("textExcerpt")).hasSizeLessThanOrEqualTo(120).startsWith("Practiqueu el balancí amb calma").doesNotContain("\n");
        assertThat(d14.getDate("activityAt").toInstant()).isEqualTo(NOW);

        // Validation (400), a dog that is not ACTIVE (422), an unknown dog (404).
        assertThat(code(createTask("estel", "s10f-d-duna", " ", null, 400))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(createTask("estel", "s10f-d-duna", "x".repeat(2001), null, 400))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(createTask("estel", "s10f-d-rock", "Rampa", null, 422))).isEqualTo("DOG_NOT_ACTIVE");
        assertThat(code(createTask("admin", "s10f-d-missing", "Rampa", null, 404))).isEqualTo("NOT_FOUND");

        // N-20 to the owner only (not Joan, her family group): APP + EMAIL, in the owner's language.
        dispatch();
        var n20 = notifications("N-20");
        assertThat(n20).extracting(n -> n.getString("channel") + " " + n.getString("accountId")).containsExactly("APP s10f-laura", "EMAIL s10f-laura");
        var app = n20.getFirst().get("variables", Document.class);
        assertThat(app.getString("dog_name")).isEqualTo("Duna"); assertThat(app.getString("instructor_name")).isEqualTo("Estel");
        assertThat(app.getString("task_excerpt")).isEqualTo(d14.getString("textExcerpt")); assertThat(app.getString("action")).isEqualTo("OPEN_TASKS");
        assertThat(app.getString("entityId")).isEqualTo("s10f-d-duna");
        var mail = ((FakeEmailSender) email).lastTo("s10f-laura@example.test");
        assertThat(mail.subject()).isEqualTo("Tasca nova per a Duna"); assertThat(mail.text()).contains("Estel t'ha posat una tasca per a Duna: «Practiqueu el balancí");
        dispatch();
        assertThat(notifications("N-20")).as("a redelivery notifies once").hasSize(2);

        // The member sees the task of her own dog; the family group's member does not (§13-13).
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna", null, as("laura"), 200).path("items")).singleElement()
                .satisfies(item -> { assertThat(item.path("id").asText()).isEqualTo(id); assertThat(item.path("createdBy").path("accountId").isNull()).isTrue(); });
        assertThat(code(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna", null, as("joan"), 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(code(call(HttpMethod.GET, "/tasks/" + id, null, as("joan"), 404))).isEqualTo("NOT_FOUND");

        // PATCH with the version read → TaskUpdated, no notification, the D14 excerpt follows; a stale version → 409.
        clock.setInstant(NOW.plusSeconds(600));
        var edited = call(HttpMethod.PATCH, "/tasks/" + id, Map.of("text", "Practiqueu el balancí dos cops per setmana", "version", 0), as("marc"), 200);
        assertThat(edited.path("version").asLong()).isEqualTo(1); assertThat(edited.path("text").asText()).isEqualTo("Practiqueu el balancí dos cops per setmana");
        assertThat(task(id).get("updatedBy", Document.class).getString("displayName")).isEqualTo("Marc");
        assertThat(code(call(HttpMethod.PATCH, "/tasks/" + id, Map.of("text", "Una altra", "version", 0), as("estel"), 409))).isEqualTo("STALE_VERSION");
        assertThat(eventsOf("TaskUpdated")).hasSize(1);
        assertThat(row(id).getString("textExcerpt")).isEqualTo("Practiqueu el balancí dos cops per setmana");
        assertThat(row(id).getDate("activityAt").toInstant()).as("an edit does not move activityAt").isEqualTo(NOW);

        // Completion by the owner: doneBy with her name and gender, completedAt on D14 (not activityAt); a second one → 422.
        clock.setInstant(NOW.plusSeconds(3600));
        var done = call(HttpMethod.POST, "/tasks/" + id + "/completion", null, as("laura"), 200);
        assertThat(done.path("state").asText()).isEqualTo("DONE"); assertThat(done.path("doneAt").asText()).isEqualTo(NOW.plusSeconds(3600).toString());
        assertThat(done.path("doneBy").path("role").asText()).isEqualTo("MEMBER"); assertThat(done.path("doneBy").path("displayName").asText()).isEqualTo("Laura");
        assertThat(done.path("doneBy").path("gender").asText()).isEqualTo("FEMALE");
        assertThat(code(call(HttpMethod.POST, "/tasks/" + id + "/completion", null, as("estel"), 422))).isEqualTo("TASK_ALREADY_DONE");
        assertThat(row(id).getDate("completedAt").toInstant()).isEqualTo(NOW.plusSeconds(3600)); assertThat(row(id).getDate("activityAt").toInstant()).isEqualTo(NOW);
        assertThat(eventsOf("TaskCompleted")).singleElement().satisfies(e -> {
            assertThat(e.get("payload", Document.class).get("by", Document.class)).isEqualTo(new Document("accountId", "s10f-laura").append("role", "MEMBER"));
            assertThat(e.get("payload", Document.class).getString("memberId")).isEqualTo("s10f-m-laura");
        });
        // N-21 to every active instructor (the former one has no account in the club), each in their own language.
        dispatch();
        var n21 = notifications("N-21");
        assertThat(n21).extracting(n -> n.getString("channel") + " " + n.getString("accountId") + " " + n.getString("locale"))
                .containsExactly("APP s10f-estel ca", "APP s10f-marc es", "APP s10f-nuria en");
        var variables = n21.getFirst().get("variables", Document.class);
        assertThat(variables.getString("member_name")).isEqualTo("Laura Example"); assertThat(variables.getString("dog_name")).isEqualTo("Duna");
        assertThat(variables.getString("gender")).isEqualTo("FEMALE"); assertThat(variables.getString("action")).isEqualTo("OPEN_DOG");
        assertThat(variables.getString("task_excerpt")).isEqualTo("Practiqueu el balancí dos cops per setmana");

        // Reopening: MEMBER → 403; staff → PENDING without doneAt/doneBy/completedAt, TaskReopened; again → 422 TASK_NOT_DONE.
        assertThat(code(call(HttpMethod.POST, "/tasks/" + id + "/reopening", null, as("laura"), 403))).isEqualTo("FORBIDDEN");
        var reopened = call(HttpMethod.POST, "/tasks/" + id + "/reopening", null, as("admin"), 200);
        assertThat(reopened.path("state").asText()).isEqualTo("PENDING"); assertThat(reopened.path("doneAt").isNull()).isTrue(); assertThat(reopened.path("doneBy").isNull()).isTrue();
        assertThat(row(id).containsKey("completedAt")).isFalse();
        assertThat(code(call(HttpMethod.POST, "/tasks/" + id + "/reopening", null, as("estel"), 422))).isEqualTo("TASK_NOT_DONE");
        assertThat(eventsOf("TaskReopened")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class).keySet()).containsExactly("taskId", "dogId", "by"));

        // Deletion (also of a done task): deletedAt, hidden D14 row, out of GET /tasks except ADMIN's includeDeleted; then 404 everywhere.
        call(HttpMethod.POST, "/tasks/" + id + "/completion", null, as("estel"), 200);
        String deletion = key();
        call(HttpMethod.DELETE, "/tasks/" + id, null, as("estel"), 204, deletion);
        call(HttpMethod.DELETE, "/tasks/" + id, null, as("estel"), 204, deletion);
        assertThat(code(call(HttpMethod.DELETE, "/tasks/" + id, null, as("estel"), 404, key()))).isEqualTo("NOT_FOUND");
        assertThat(task(id).get("deletedBy", Document.class).getString("displayName")).isEqualTo("Estel"); assertThat(row(id).getBoolean("hidden")).isTrue();
        assertThat(eventsOf("TaskDeleted")).hasSize(1);
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna", null, as("admin"), 200).path("items")).isEmpty();
        var withDeleted = call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna&includeDeleted=true", null, as("admin"), 200).path("items");
        assertThat(withDeleted).singleElement().satisfies(item -> assertThat(item.path("deletedAt").asText()).isNotEmpty());
        for (String who : List.of("estel", "admin", "laura")) { assertThat(code(call(HttpMethod.GET, "/tasks/" + id, null, as(who), 404))).isEqualTo("NOT_FOUND"); }
        assertThat(code(call(HttpMethod.POST, "/tasks/" + id + "/completion", null, as("admin"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.PATCH, "/tasks/" + id, Map.of("text", "Una altra", "version", 3), as("estel"), 404))).isEqualTo("NOT_FOUND");
        // No notification for TaskUpdated, TaskDeleted, TaskReopened: only one N-20 pair and the N-21 of each completion.
        dispatch();
        assertThat(notifications("N-20")).hasSize(2); assertThat(notifications("N-21")).hasSize(6);
    }

    @Test void T_10_15_includeDoneDefaultsToTheWholeHistoryAndStatePicksOne() throws Exception {
        var first = createTask("estel", "s10f-d-duna", "Primera", null, 201).path("id").asText();
        clock.setInstant(NOW.plusSeconds(60)); var second = createTask("estel", "s10f-d-duna", "Segona", null, 201).path("id").asText();
        call(HttpMethod.POST, "/tasks/" + first + "/completion", null, as("laura"), 200);
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna", null, as("laura"), 200).path("items").findValuesAsText("id")).as("createdAt desc, DONE included")
                .containsExactly(second, first);
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna&includeDone=false", null, as("estel"), 200).path("items").findValuesAsText("id")).containsExactly(second);
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna&state=DONE", null, as("estel"), 200).path("items").findValuesAsText("id")).containsExactly(first);
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna&size=1&page=1", null, as("estel"), 200).path("items").findValuesAsText("id")).containsExactly(first);
        assertThat(code(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna&size=500", null, as("estel"), 400))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna&includeDeleted=true", null, as("estel"), 403))).isEqualTo("FORBIDDEN");
    }

    @Test @AuditCovers(AuditAction.DOG_UPDATED)
    void T_10_15_T_10_22_theImpersonatedOwnerCompletesAuditedAndStaffRoutesRejectTheToken() throws Exception {
        var id = createTask("estel", "s10f-d-duna", "Treballar l'espera a la línia de sortida", null, 201).path("id").asText();
        var token = impersonating("s10f-m-laura");
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-duna", null, token, 200).path("items").findValuesAsText("id")).containsExactly(id);
        var done = call(HttpMethod.POST, "/tasks/" + id + "/completion", null, token, 200);
        assertThat(done.path("doneBy").path("role").asText()).isEqualTo("MEMBER"); assertThat(done.path("doneBy").path("displayName").asText()).isEqualTo("Laura");
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("entityType").is("Task").and("entityId").is(id)), Document.class, "audit_entries");
        assertThat(audit.getString("action")).isEqualTo("DOG_UPDATED"); assertThat(audit.getString("origin")).isEqualTo("BACKOFFICE");
        assertThat(audit.getString("impersonatedMemberId")).isEqualTo("s10f-m-laura"); assertThat(audit.getString("actorAccountId")).isEqualTo("s10f-admin");
        assertThat(audit.getString("memberId")).isEqualTo("s10f-m-laura");
        assertThat(audit.getList("changes", Document.class)).extracting(c -> c.getString("path")).contains("state", "doneAt");
        assertThat(eventsOf("TaskCompleted")).singleElement().satisfies(e -> {
            assertThat(e.getString("actorAccountId")).isEqualTo("s10f-admin"); assertThat(e.getString("impersonatedMemberId")).isEqualTo("s10f-m-laura");
        });
        // The instructor writes, D14 and the observations refuse the impersonation token (IMPERSONATION_DENIED).
        for (var denied : List.of(List.of("POST", "/tasks"), List.of("PATCH", "/tasks/" + id), List.of("DELETE", "/tasks/" + id), List.of("POST", "/tasks/" + id + "/reopening"),
                List.of("GET", "/tasks/" + id), List.of("GET", "/followup"), List.of("GET", "/followup/unread-count"), List.of("POST", "/followup/read-all"),
                List.of("PUT", "/dogs/s10f-d-duna/observations"))) {
            Object body = denied.get(0).equals("GET") || denied.get(0).equals("DELETE") ? null : Map.of("dogId", "s10f-d-duna", "text", "x", "version", 0);
            assertThat(code(call(HttpMethod.valueOf(denied.get(0)), denied.get(1), body, token, 403, key()))).as(denied.toString()).isEqualTo("IMPERSONATION_DENIED");
        }
        // A MEMBER on the staff routes → 403 FORBIDDEN.
        for (var forbidden : List.of(List.of("POST", "/tasks"), List.of("PATCH", "/tasks/" + id), List.of("DELETE", "/tasks/" + id), List.of("GET", "/followup"),
                List.of("GET", "/followup/unread-count"), List.of("POST", "/followup/read-all"), List.of("PUT", "/dogs/s10f-d-duna/observations"))) {
            Object body = forbidden.get(0).equals("GET") || forbidden.get(0).equals("DELETE") ? null : Map.of("dogId", "s10f-d-duna", "text", "x", "version", 0);
            assertThat(code(call(HttpMethod.valueOf(forbidden.get(0)), forbidden.get(1), body, as("laura"), 403, key()))).as(forbidden.toString()).isEqualTo("FORBIDDEN");
        }
        // Another club's task, dog, attachment and follow-up row → 404.
        mongo.insert(new Document("_id", "s10f-t-other").append("clubId", OTHER).append("dogId", "s10f-d-other").append("memberId", "s10f-m-other").append("text", "Aliena")
                .append("state", "PENDING").append("attachmentCount", 0).append("version", 0), "tasks");
        mongo.insert(new Document("_id", "s10f-f-other").append("clubId", OTHER).append("kind", "TASK").append("dogId", "s10f-d-other").append("hidden", false), "followup_items");
        mongo.insert(new Document("_id", "s10f-a-other").append("clubId", OTHER).append("entityType", "TASK").append("entityId", "s10f-t-other").append("fileKey", "s10f-a-other")
                .append("name", "a.pdf").append("mimeType", "application/pdf").append("sizeBytes", 4).append("version", 0), "attachments");
        assertThat(code(call(HttpMethod.GET, "/tasks/s10f-t-other", null, as("admin"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.POST, "/tasks/s10f-t-other/completion", null, as("estel"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.GET, "/tasks?dogId=s10f-d-other", null, as("estel"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(createTask("admin", "s10f-d-other", "Aliena", null, 404))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.PUT, "/dogs/s10f-d-other/observations", Map.of("text", "x", "version", 0), as("admin"), 404, key()))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.POST, "/followup/s10f-f-other/read", null, as("estel"), 404, key()))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.DELETE, "/attachments/s10f-a-other", null, as("admin"), 404, key()))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.GET, "/attachments?entityType=TASK&entityId=s10f-t-other", null, as("admin"), 404))).isEqualTo("NOT_FOUND");
    }

    /** T-10-25: a double click with one key is one task; two simultaneous completions give one 200, one 422 and a single N-21. */
    @Test void T_10_25_aDoubleClickIsOneTaskAndTwoSimultaneousCompletionsOneN21() throws Exception {
        String idempotency = key(); var body = Map.of("dogId", "s10f-d-duna", "text", "Practiqueu el balancí");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var creations = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                creations.add(pool.submit(() -> { start.await(); return mvc.perform(request(HttpMethod.POST, "/api/v1/tasks").header("Host", HOST).with(as("estel"))
                        .header("Idempotency-Key", idempotency).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse().getStatus(); }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>(); for (var f : creations) { statuses.add(f.get(60, TimeUnit.SECONDS)); }
            assertThat(statuses).contains(201).allMatch(status -> status == 201 || status == 409);
            assertThat(count("tasks", Criteria.where("dogId").is("s10f-d-duna"))).isEqualTo(1);
            assertThat(eventsOf("TaskCreated")).hasSize(1);
            String id = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "tasks").getString("_id");

            var go = new CountDownLatch(1);
            var completions = new ArrayList<Future<Integer>>();
            for (String who : List.of("laura", "estel")) {
                completions.add(pool.submit(() -> { go.await(); return mvc.perform(request(HttpMethod.POST, "/api/v1/tasks/" + id + "/completion").header("Host", HOST).with(as(who)))
                        .andReturn().getResponse().getStatus(); }));
            }
            go.countDown();
            var outcomes = new ArrayList<Integer>(); for (var f : completions) { outcomes.add(f.get(60, TimeUnit.SECONDS)); }
            assertThat(outcomes).containsExactlyInAnyOrder(200, 422);
        } finally { pool.shutdownNow(); }
        assertThat(eventsOf("TaskCompleted")).hasSize(1);
        dispatch(); dispatch();
        assertThat(notifications("N-21")).hasSize(3);
    }

    // ---------------------------------------------------------------- attachments (R-10-11)

    @Test void T_10_16_attachmentsFollowTheirEntityPurposeLimitAndRoles() throws Exception {
        byte[] pdf = "%PDF fictional".getBytes();
        // The upload URL: 30 MB → FILE_TOO_LARGE{maxSizeMb: 25}; an .exe → FILE_TYPE_NOT_ALLOWED; a 20 MB video/quicktime → 201.
        var tooLarge = call(HttpMethod.POST, "/attachments/upload-url", Map.of("purpose", "TASK", "fileName", "vídeo_balancí.mp4", "mimeType", "video/mp4",
                "sizeBytes", 30L * 1024 * 1024), as("estel"), 400);
        assertThat(code(tooLarge)).isEqualTo("FILE_TOO_LARGE"); assertThat(tooLarge.path("details").path("maxSizeMb").asInt()).isEqualTo(25);
        assertThat(code(call(HttpMethod.POST, "/attachments/upload-url", Map.of("purpose", "TASK", "fileName", "eina.exe", "mimeType", "application/x-msdownload",
                "sizeBytes", 4), as("estel"), 400))).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(call(HttpMethod.POST, "/attachments/upload-url", Map.of("purpose", "TASK", "fileName", "vídeo_balancí.mov", "mimeType", "video/quicktime",
                "sizeBytes", 20L * 1024 * 1024), as("estel"), 201).path("fileKey").asText()).isNotEmpty();

        // A task created with an attachment: one transaction; a DOG_DOCUMENT key instead → 422 and no task at all.
        String dogDocument = upload(as("admin"), "DOG_DOCUMENT", "cartilla.pdf", "application/pdf", pdf);
        assertThat(code(call(HttpMethod.POST, "/tasks", Map.of("dogId", "s10f-d-duna", "text", "Amb vídeo", "attachmentIds", List.of(dogDocument)), as("admin"), 422, key())))
                .isEqualTo("ATTACHMENT_ENTITY_MISMATCH");
        assertThat(count("tasks", new Criteria())).isZero(); assertThat(count("followup_items", new Criteria())).isZero(); assertThat(eventsOf("TaskCreated")).isEmpty();
        String video = upload(as("estel"), "TASK", "vídeo_balancí.mp4", "video/mp4", pdf);
        var task = createTask("estel", "s10f-d-duna", "Mireu el vídeo", List.of(video, video), 201);
        String taskId = task.path("id").asText();
        assertThat(task.path("attachments")).singleElement().satisfies(a -> {
            assertThat(a.path("name").asText()).isEqualTo("vídeo_balancí.mp4"); assertThat(a.path("mimeType").asText()).isEqualTo("video/mp4");
            assertThat(a.path("url").asText()).startsWith("/api/v1/attachments/files/" + video + "?expires=");
        });
        assertThat(task(taskId).getInteger("attachmentCount")).isEqualTo(1);
        assertThat(eventsOf("AttachmentAdded")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .isEqualTo(new Document("attachmentId", video).append("entityType", "TASK").append("entityId", taskId)));

        // POST /attachments: the same key again is the same attachment; a DOG_DOCUMENT key → 422; the eleventh → ATTACHMENT_LIMIT_REACHED{max}.
        var again = call(HttpMethod.POST, "/attachments", Map.of("entityType", "TASK", "entityId", taskId, "fileKey", video, "name", "vídeo_balancí.mp4"), as("estel"), 201);
        assertThat(again.path("id").asText()).isEqualTo(video); assertThat(eventsOf("AttachmentAdded")).hasSize(1);
        assertThat(code(call(HttpMethod.POST, "/attachments", Map.of("entityType", "TASK", "entityId", taskId, "fileKey", dogDocument, "name", "cartilla.pdf"),
                as("admin"), 422))).isEqualTo("ATTACHMENT_ENTITY_MISMATCH");
        for (int i = 2; i <= 10; i++) {
            String file = upload(as("estel"), "TASK", "foto" + i + ".pdf", "application/pdf", pdf);
            call(HttpMethod.POST, "/attachments", Map.of("entityType", "TASK", "entityId", taskId, "fileKey", file, "name", "foto" + i + ".pdf"), as("estel"), 201);
        }
        String eleventh = upload(as("estel"), "TASK", "foto11.pdf", "application/pdf", pdf);
        var limit = call(HttpMethod.POST, "/attachments", Map.of("entityType", "TASK", "entityId", taskId, "fileKey", eleventh, "name", "foto11.pdf"), as("estel"), 422);
        assertThat(code(limit)).isEqualTo("ATTACHMENT_LIMIT_REACHED"); assertThat(limit.path("details").path("max").asInt()).isEqualTo(10);
        assertThat(task(taskId).getInteger("attachmentCount")).isEqualTo(10);

        // Who reads: the owner reads the task's; DOG_OBSERVATIONS never a member (404); staff do.
        assertThat(call(HttpMethod.GET, "/attachments?entityType=TASK&entityId=" + taskId, null, as("laura"), 200).path("items")).hasSize(10);
        assertThat(code(call(HttpMethod.GET, "/attachments?entityType=TASK&entityId=" + taskId, null, as("joan"), 404))).isEqualTo("NOT_FOUND");
        String observation = upload(as("marc"), "DOG_OBSERVATIONS", "radiografia.pdf", "application/pdf", pdf);
        call(HttpMethod.POST, "/attachments", Map.of("entityType", "DOG_OBSERVATIONS", "entityId", "s10f-d-duna", "fileKey", observation, "name", "radiografia.pdf"), as("marc"), 201);
        assertThat(code(call(HttpMethod.GET, "/attachments?entityType=DOG_OBSERVATIONS&entityId=s10f-d-duna", null, as("laura"), 404))).isEqualTo("NOT_FOUND");
        assertThat(call(HttpMethod.GET, "/attachments?entityType=DOG_OBSERVATIONS&entityId=s10f-d-duna", null, as("admin"), 200).path("items").findValuesAsText("id"))
                .containsExactly(observation);
        // Who writes: a MEMBER on TASK → 403, on her own dog's note → 201; staff on the member's note → 403.
        assertThat(code(call(HttpMethod.POST, "/attachments", Map.of("entityType", "TASK", "entityId", taskId, "fileKey", eleventh, "name", "x.pdf"), as("laura"), 403)))
                .isEqualTo("FORBIDDEN");
        String note = upload(as("laura"), "INSTRUCTOR_NOTE", "foto_balancí.pdf", "application/pdf", pdf);
        call(HttpMethod.POST, "/attachments", Map.of("entityType", "INSTRUCTOR_NOTE", "entityId", "s10f-d-duna", "fileKey", note, "name", "foto_balancí.pdf"), as("laura"), 201);
        assertThat(code(call(HttpMethod.POST, "/attachments", Map.of("entityType", "INSTRUCTOR_NOTE", "entityId", "s10f-d-duna", "fileKey", note, "name", "x.pdf"),
                as("estel"), 403))).isEqualTo("FORBIDDEN");

        // DELETE: removedAt, out of the lists, the task's count − 1, AttachmentRemoved; the same key replays 204; again → 404.
        String removal = key();
        call(HttpMethod.DELETE, "/attachments/" + video, null, as("admin"), 204, removal);
        call(HttpMethod.DELETE, "/attachments/" + video, null, as("admin"), 204, removal);
        assertThat(code(call(HttpMethod.DELETE, "/attachments/" + video, null, as("admin"), 404, key()))).isEqualTo("NOT_FOUND");
        var removed = mongo.findById(video, Document.class, "attachments");
        assertThat(removed.getDate("removedAt")).isNotNull(); assertThat(removed.getString("removedByAccountId")).isEqualTo("s10f-admin");
        assertThat(task(taskId).getInteger("attachmentCount")).isEqualTo(9);
        assertThat(call(HttpMethod.GET, "/attachments?entityType=TASK&entityId=" + taskId, null, as("estel"), 200).path("items").findValuesAsText("id")).doesNotContain(video);
        assertThat(call(HttpMethod.GET, "/tasks/" + taskId, null, as("estel"), 200).path("attachments")).hasSize(9);
        assertThat(eventsOf("AttachmentRemoved")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .isEqualTo(new Document("attachmentId", video).append("entityType", "TASK").append("entityId", taskId)));
        assertThat(code(call(HttpMethod.DELETE, "/attachments/" + note, null, as("estel"), 403, key()))).isEqualTo("FORBIDDEN");
        call(HttpMethod.DELETE, "/attachments/" + note, null, as("laura"), 204, key());
        // No notification for attachments.
        dispatch();
        assertThat(count("notifications", Criteria.where("code").nin("N-20"))).isZero();
    }

    // ---------------------------------------------------------------- observations (R-10-12)

    @Test @AuditCovers(AuditAction.DOG_UPDATED)
    void T_10_17_observationsAreVersionedAuditedAndNeverReachAMember() throws Exception {
        var card = call(HttpMethod.GET, "/dogs/s10f-d-duna/instructor-card", null, as("estel"), 200);
        assertThat(card.path("observations").path("text").isNull()).isTrue(); assertThat(card.path("observations").path("version").asLong()).isZero();
        String secret = "Va molt bé amb reforç de pilota; fictional private remark 7431";
        String idempotency = key();
        var saved = call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", secret, "version", 0), as("marc"), 200, idempotency);
        assertThat(saved.path("text").asText()).isEqualTo(secret); assertThat(saved.path("updatedByName").asText()).isEqualTo("Marc");
        assertThat(saved.path("updatedAt").asText()).isEqualTo(NOW.toString()); assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", secret, "version", 0), as("marc"), 200, idempotency)).isEqualTo(saved);
        var dog = mongo.findById("s10f-d-duna", Document.class, "dogs");
        assertThat(dog.getString("remarks")).isEqualTo(secret);
        assertThat(dog.get("remarksMeta", Document.class).getString("updatedByAccountId")).isEqualTo("s10f-marc");
        assertThat(eventsOf("DogUpdated")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class).get("diff", Document.class).get("remarks", Document.class))
                .isEqualTo(new Document("before", null).append("after", secret)));
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("entityType").is("Dog").and("entityId").is("s10f-d-duna")), Document.class, "audit_entries");
        assertThat(audit.getString("action")).isEqualTo("DOG_UPDATED"); assertThat(audit.getString("memberId")).isEqualTo("s10f-m-laura");
        assertThat(audit.getList("changes", Document.class)).extracting(c -> c.getString("path")).contains("remarks");
        // A stale version → 409; too long → 400; the same text again changes nothing.
        assertThat(code(call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", "Una altra", "version", 0), as("estel"), 409, key()))).isEqualTo("STALE_VERSION");
        assertThat(code(call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", "x".repeat(2001), "version", 1), as("estel"), 400, key()))).isEqualTo("VALIDATION_ERROR");
        assertThat(call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", secret, "version", 1), as("estel"), 200, key()).path("version").asLong()).isEqualTo(1);
        assertThat(eventsOf("DogUpdated")).hasSize(1);
        // The card's block shows it; no D14 row and no notification.
        var after = call(HttpMethod.GET, "/dogs/s10f-d-duna/instructor-card", null, as("admin"), 200).path("observations");
        assertThat(after.path("text").asText()).isEqualTo(secret); assertThat(after.path("updatedByName").asText()).isEqualTo("Marc"); assertThat(after.path("version").asLong()).isEqualTo(1);
        dispatch();
        assertThat(count("followup_items", new Criteria())).isZero(); assertThat(count("notifications", new Criteria())).isZero();
        // Never on /me/*: not the field, not its text.
        for (String path : List.of("/me/dogs", "/me/history", "/me/home")) {
            var response = mvc.perform(request(HttpMethod.GET, "/api/v1" + path).header("Host", HOST).with(as("laura"))).andReturn().getResponse();
            assertThat(response.getStatus()).as(path + " " + response.getContentAsString()).isEqualTo(200);
            assertThat(response.getContentAsString()).as(path).doesNotContain(secret, "remarks", "remarksMeta", "observations");
        }
        // Another write of the dog (the member's note) does not make the observations stale: their version is their own.
        call(HttpMethod.PUT, "/me/dogs/s10f-d-duna/instructor-note", Map.of("text", "Nota de la Laura"), as("laura"), 200);
        assertThat(mongo.findById("s10f-d-duna", Document.class, "dogs").get("version", Number.class).longValue()).isEqualTo(2);
        assertThat(call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", secret + " i ara salta", "version", 1), as("estel"), 200, key())
                .path("version").asLong()).isEqualTo(2);
        // Emptying clears it.
        var cleared = call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", "", "version", 2), as("estel"), 200, key());
        assertThat(cleared.path("text").asText()).isEmpty(); assertThat(cleared.path("version").asLong()).isEqualTo(3);
        assertThat(mongo.findById("s10f-d-duna", Document.class, "dogs").get("remarks")).isNull();
        // TASKS off → 404 MODULE_DISABLED.
        modules(Module.WAITLIST, Module.FAQ, Module.PUSH);
        assertThat(code(call(HttpMethod.PUT, "/dogs/s10f-d-duna/observations", Map.of("text", "x", "version", 3), as("estel"), 404, key()))).isEqualTo("MODULE_DISABLED");
    }

    /** Step 12 (S04 R-04-06, E3-T17 round 2): the reused dog of a pending readmission is frozen for the observations and their files too. */
    @Test void R_04_06_observationsOfAPendingReadmissionsReusedDogAnswer409() throws Exception {
        String file = upload(as("estel"), "DOG_OBSERVATIONS", "radiografia.pdf", "application/pdf", "%PDF fictional".getBytes());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s10f-d-toby")), new Update().set("status", "PENDING").set("readmissionRequest", new Document("submitted", new Document())), "dogs");
        var frozen = call(HttpMethod.PUT, "/dogs/s10f-d-toby/observations", Map.of("text", "No", "version", 0), as("estel"), 409, key());
        assertThat(code(frozen)).isEqualTo("INVALID_STATE"); assertThat(frozen.path("details").path("reason").asText()).isEqualTo("READMISSION_PENDING");
        var attach = call(HttpMethod.POST, "/attachments", Map.of("entityType", "DOG_OBSERVATIONS", "entityId", "s10f-d-toby", "fileKey", file, "name", "radiografia.pdf"), as("estel"), 409);
        assertThat(frozen.path("details").path("reason").asText()).isEqualTo(attach.path("details").path("reason").asText());
        assertThat(mongo.findById("s10f-d-toby", Document.class, "dogs").get("remarks")).isNull();
        assertThat(eventsOf("DogUpdated")).isEmpty(); assertThat(count("audit_entries", new Criteria())).isZero();
    }

    // ---------------------------------------------------------------- D14 (R-10-13) and the S03 consumers

    @Test void T_10_18_T_10_06_d14ListsUnreadFirstPerAccountWithReadMarksAndTheNoteRow() throws Exception {
        var estelTask = createTask("estel", "s10f-d-duna", "Tasca de l'Estel", null, 201).path("id").asText();
        clock.setInstant(NOW.plusSeconds(3600));
        var marcTask = createTask("marc", "s10f-d-toby", "Tasca d'en Marc", null, 201).path("id").asText();
        clock.setInstant(NOW.plusSeconds(7200));
        call(HttpMethod.PUT, "/me/dogs/s10f-d-duna/instructor-note", Map.of("text", "A veure si treballem una mica el doble a classe"), as("laura"), 200);
        dispatch();
        String noteRow = com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository.noteRowId(CLUB, "s10f-d-duna");
        var note = mongo.findById(noteRow, Document.class, "followup_items");
        assertThat(note.getString("kind")).isEqualTo("MEMBER_NOTE"); assertThat(note.getString("authorAccountId")).isEqualTo("s10f-laura");
        assertThat(note.getString("authorRole")).isEqualTo("MEMBER"); assertThat(note.getString("authorName")).isEqualTo("Laura");
        assertThat(note.getDate("activityAt").toInstant()).isEqualTo(NOW.plusSeconds(7200));
        // N-22 to every active instructor.
        assertThat(notifications("N-22")).extracting(n -> n.getString("accountId")).containsExactly("s10f-estel", "s10f-marc", "s10f-nuria");
        assertThat(notifications("N-22").getFirst().get("variables", Document.class).getString("member_name")).isEqualTo("Laura Example");

        // Estel: Marc's task and the note unread (newest first), her own task read; Marc: the note and Estel's task unread.
        var estel = followup("estel");
        assertThat(estel.path("items").findValuesAsText("id")).containsExactly(noteRow, rowId(marcTask), rowId(estelTask));
        assertThat(estel.path("items").findValues("unread").stream().map(JsonNode::asBoolean).toList()).containsExactly(true, true, false);
        var first = estel.path("items").get(0);
        assertThat(first.path("kind").asText()).isEqualTo("MEMBER_NOTE"); assertThat(first.path("dogName").asText()).isEqualTo("Duna");
        assertThat(first.path("memberName").asText()).isEqualTo("Laura Example"); assertThat(first.path("levelCode").asText()).isEqualTo("C");
        assertThat(first.path("authorGender").asText()).isEqualTo("FEMALE"); assertThat(first.path("taskId").isNull()).isTrue();
        assertThat(estel.path("items").get(1).path("authorGender").isNull()).isTrue();
        assertThat(unread("estel")).isEqualTo(2); assertThat(unread("marc")).isEqualTo(2); assertThat(unread("admin")).isEqualTo(3);

        // A click reads one row for Estel only; read-all for Marc leaves 0 for him and changes nothing for the others.
        call(HttpMethod.POST, "/followup/" + noteRow + "/read", null, as("estel"), 204, key());
        assertThat(unread("estel")).isEqualTo(1);
        call(HttpMethod.POST, "/followup/read-all", null, as("marc"), 204, key());
        assertThat(unread("marc")).isZero(); assertThat(unread("estel")).isEqualTo(1); assertThat(unread("admin")).isEqualTo(3);
        assertThat(followup("estel").path("items").findValuesAsText("id")).as("unread first, then the rest by activityAt desc").containsExactly(rowId(marcTask), noteRow, rowId(estelTask));
        // The admin's dashboard counter is the same count (cached 30 s), and fresh right after the read-all (evicted after the commit).
        assertThat(call(HttpMethod.GET, "/dashboard/counters", null, as("admin"), 200).path("followUpUnread").asInt()).isEqualTo(3);
        call(HttpMethod.POST, "/followup/read-all", null, as("admin"), 204, key());
        assertThat(call(HttpMethod.GET, "/dashboard/counters", null, as("admin"), 200).path("followUpUnread").asInt()).isZero();

        // Completing a task does not make it unread (completedAt only).
        clock.setInstant(NOW.plusSeconds(9000));
        call(HttpMethod.POST, "/tasks/" + marcTask + "/completion", null, as("joan"), 200);
        assertThat(unread("marc")).isZero(); assertThat(unread("estel")).isEqualTo(1);

        // A changed note is unread again for everyone but its author (Estel had read it, Marc read all), one row only.
        clock.setInstant(NOW.plusSeconds(10800));
        call(HttpMethod.PUT, "/me/dogs/s10f-d-duna/instructor-note", Map.of("text", "Ara també el balancí"), as("laura"), 200);
        dispatch();
        assertThat(count("followup_items", Criteria.where("kind").is("MEMBER_NOTE"))).isEqualTo(1);
        assertThat(unread("estel")).isEqualTo(2); assertThat(unread("marc")).isEqualTo(1); assertThat(unread("admin")).isEqualTo(1);
        assertThat(mongo.findById(noteRow, Document.class, "followup_items").getString("textExcerpt")).isEqualTo("Ara també el balancí");
        // The same MemberNoteChanged consumed twice leaves one row, and an older replay does not make it unread again.
        call(HttpMethod.POST, "/followup/read-all", null, as("marc"), 204, key());
        var changed = eventsOf("MemberNoteChanged").getLast();
        var envelope = mapper.readValue(changed.getString("eventJson"), com.agilityhub.core.clubs.followup.domain.CensusForeignEvent.class);
        noteConsumer.handle(changed.getString("_id"), envelope); tx.executeWithoutResult(status -> { try { noteConsumer.handle(changed.getString("_id"), envelope); } catch (Exception e) { throw new IllegalStateException(e); } });
        assertThat(count("followup_items", Criteria.where("kind").is("MEMBER_NOTE"))).isEqualTo(1); assertThat(unread("marc")).isZero();

        // Universal filters (kind, unread, authorAccountId, memberId, dogId) and fields; an undeclared filter → 400.
        assertThat(followup("estel", "filter", "kind:eq:TASK").path("items").findValuesAsText("kind")).containsOnly("TASK").hasSize(2);
        assertThat(followup("estel", "filter", "unread:eq:true").path("items").findValuesAsText("id")).containsExactly(noteRow, rowId(marcTask));
        assertThat(followup("estel", "filter", "authorAccountId:eq:s10f-marc").path("items").findValuesAsText("id")).containsExactly(rowId(marcTask));
        assertThat(followup("estel", "filter", "memberId:eq:s10f-m-joan", "filter", "dogId:eq:s10f-d-toby").path("totalItems").asInt()).isEqualTo(1);
        var sparse = followup("estel", "fields", "dogName");
        assertThat(sparse.path("items").get(0).size()).isEqualTo(2); assertThat(sparse.path("items").get(0).path("dogName").asText()).isEqualTo("Duna");
        assertThat(code(call(HttpMethod.GET, "/followup?filter=textExcerpt:contains:balancí", null, as("estel"), 400))).isEqualTo("INVALID_FILTER");
        // levels.enabled = false → levelCode null (R-10-16).
        parameter("levels.enabled", false);
        assertThat(followup("estel").path("items").get(0).path("levelCode").isNull()).isTrue();

        // TaskDeleted hides the row; its read-mark id is pruned on the next click.
        call(HttpMethod.DELETE, "/tasks/" + marcTask, null, as("marc"), 204, key());
        assertThat(followup("estel").path("items").findValuesAsText("id")).doesNotContain(rowId(marcTask));
        assertThat(code(call(HttpMethod.POST, "/followup/" + rowId(marcTask) + "/read", null, as("estel"), 404, key()))).isEqualTo("NOT_FOUND");
        assertThat(unread("estel")).isEqualTo(1);
        // Emptying the note hides its row and notifies nobody.
        long n22 = notifications("N-22").size();
        clock.setInstant(NOW.plusSeconds(14400));
        call(HttpMethod.PUT, "/me/dogs/s10f-d-duna/instructor-note", Map.of("text", ""), as("laura"), 200);
        dispatch();
        assertThat(mongo.findById(noteRow, Document.class, "followup_items").getBoolean("hidden")).isTrue();
        assertThat(notifications("N-22")).hasSize((int) n22); assertThat(unread("estel")).isZero();
    }
    private static String rowId(String taskId) { return com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository.taskRowId(taskId); }

    /** S03 R-03-14: `DogTransferred` moves the dog's tasks and D14 rows to the new owner; `DogDeactivated` keeps everything. */
    @Test void T_10_18_theTaskFollowsATransferredDog() throws Exception {
        var id = createTask("estel", "s10f-d-toby", "Pujar la rampa", null, 201).path("id").asText();
        assertThat(call(HttpMethod.GET, "/tasks/" + id, null, as("joan"), 200).path("id").asText()).isEqualTo(id);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s10f-d-toby")), new Update().set("memberId", "s10f-m-laura"), "dogs");
        publishCensus("DogTransferred", "s10f-d-toby", Map.of("dogId", "s10f-d-toby", "memberId", "s10f-m-laura", "fromMemberId", "s10f-m-joan", "toMemberId", "s10f-m-laura"));
        dispatch();
        assertThat(task(id).getString("memberId")).isEqualTo("s10f-m-laura"); assertThat(row(id).getString("memberId")).isEqualTo("s10f-m-laura");
        assertThat(call(HttpMethod.GET, "/tasks/" + id, null, as("laura"), 200).path("id").asText()).isEqualTo(id);
        assertThat(code(call(HttpMethod.GET, "/tasks/" + id, null, as("joan"), 404))).isEqualTo("NOT_FOUND");
        call(HttpMethod.POST, "/tasks/" + id + "/completion", null, as("laura"), 200);
        publishCensus("DogDeactivated", "s10f-d-toby", Map.of("dogId", "s10f-d-toby", "memberId", "s10f-m-laura", "reason", "CLUB"));
        dispatch();
        assertThat(task(id).get("deletedAt")).isNull(); assertThat(row(id).getBoolean("hidden")).isFalse();
    }

    // ---------------------------------------------------------------- round 2 (review of 27-09, ruling E64): the dog's current owner

    /** The real S03 transfer (ADMIN), which emits `DogTransferred`; the outbox is not dispatched here. */
    void transfer(String dogId, String toMemberId) throws Exception {
        call(HttpMethod.POST, "/dogs/" + dogId + "/transfer", Map.of("toMemberId", toMemberId, "reason", "Fictional S10 transfer"), as("admin"), 200);
    }
    com.agilityhub.core.clubs.followup.domain.CensusForeignEvent envelope(Document event) throws Exception {
        return mapper.readValue(event.getString("eventJson"), com.agilityhub.core.clubs.followup.domain.CensusForeignEvent.class);
    }

    /**
     * Review #1 (R-10-10 amended 27-09): with the outbox paused right after the transfer, `Task.memberId` still names Joan,
     * yet Joan gets 404 on the task, on its attachments and on its completion, and Laura, the new owner, 200. The completion
     * names Laura in `TaskCompleted` and N-21.
     */
    @Test void R_10_10_rightAfterATransferOnlyTheNewOwnerReachesTheTaskItsAttachmentsAndItsCompletion() throws Exception {
        String video = upload(as("estel"), "TASK", "rampa.mp4", "video/mp4", "%PDF fictional".getBytes());
        var id = createTask("estel", "s10f-d-toby", "Pujar la rampa a poc a poc", List.of(video), 201).path("id").asText();
        dispatch();
        String attachments = "/attachments?entityType=TASK&entityId=" + id;
        assertThat(call(HttpMethod.GET, attachments, null, as("joan"), 200).path("items").findValuesAsText("id")).containsExactly(video);
        transfer("s10f-d-toby", "s10f-m-laura");
        assertThat(task(id).getString("memberId")).as("the consumer has not run").isEqualTo("s10f-m-joan");
        assertThat(row(id).getString("memberId")).isEqualTo("s10f-m-joan");
        assertThat(code(call(HttpMethod.GET, "/tasks/" + id, null, as("joan"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.GET, attachments, null, as("joan"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.POST, "/tasks/" + id + "/completion", null, as("joan"), 404))).isEqualTo("NOT_FOUND");
        assertThat(code(call(HttpMethod.GET, "/tasks?dogId=s10f-d-toby", null, as("joan"), 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(call(HttpMethod.GET, "/tasks/" + id, null, as("laura"), 200).path("id").asText()).isEqualTo(id);
        assertThat(call(HttpMethod.GET, attachments, null, as("laura"), 200).path("items").findValuesAsText("id")).containsExactly(video);
        assertThat(call(HttpMethod.GET, "/tasks?dogId=s10f-d-toby", null, as("laura"), 200).path("items")).extracting(task -> task.path("id").asText()).containsExactly(id);
        var done = call(HttpMethod.POST, "/tasks/" + id + "/completion", null, as("laura"), 200);
        assertThat(done.path("state").asText()).isEqualTo("DONE"); assertThat(done.path("doneBy").path("displayName").asText()).isEqualTo("Laura");
        assertThat(eventsOf("TaskCompleted")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class).getString("memberId")).isEqualTo("s10f-m-laura"));
        assertThat(task(id).getString("memberId")).as("still paused").isEqualTo("s10f-m-joan");
        dispatch();
        assertThat(task(id).getString("memberId")).isEqualTo("s10f-m-laura"); assertThat(row(id).getString("memberId")).isEqualTo("s10f-m-laura");
        assertThat(notifications("N-21")).hasSize(3).allSatisfy(n -> assertThat(n.get("variables", Document.class).getString("member_name")).isEqualTo("Laura Example"));
    }

    /**
     * Review #2 (S03 R-03-14, R-10-10): Joan → Laura, then Laura → Pau, with the second `DogTransferred` delivered first and
     * the first one late: the tasks and the D14 rows end with Pau, the dog's owner in the census, whatever the order.
     */
    @Test void R_10_10_transfersDeliveredOutOfOrderEndWithTheDogsCurrentOwner() throws Exception {
        person("pau", "MEMBER", "Pau", "MALE", "ca");
        var id = createTask("estel", "s10f-d-toby", "Pujar la rampa", null, 201).path("id").asText();
        call(HttpMethod.PUT, "/me/dogs/s10f-d-toby/instructor-note", Map.of("text", "En Toby s'espanta dels túnels"), as("joan"), 200);
        dispatch();
        String noteRow = com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository.noteRowId(CLUB, "s10f-d-toby");
        clock.setInstant(NOW.plusSeconds(60)); transfer("s10f-d-toby", "s10f-m-laura");
        clock.setInstant(NOW.plusSeconds(120)); transfer("s10f-d-toby", "s10f-m-pau");
        var transfers = eventsOf("DogTransferred").stream().sorted(java.util.Comparator.comparing((Document e) -> e.getDate("occurredAt"))).toList();
        assertThat(transfers).extracting(e -> e.get("payload", Document.class).getString("toMemberId")).containsExactly("s10f-m-laura", "s10f-m-pau");
        // The second event first, then the first one, late.
        transferConsumer.handle(transfers.get(1).getString("_id"), envelope(transfers.get(1)));
        assertThat(task(id).getString("memberId")).isEqualTo("s10f-m-pau");
        transferConsumer.handle(transfers.get(0).getString("_id"), envelope(transfers.get(0)));
        assertThat(task(id).getString("memberId")).isEqualTo("s10f-m-pau");
        assertThat(row(id).getString("memberId")).isEqualTo("s10f-m-pau");
        assertThat(mongo.findById(noteRow, Document.class, "followup_items").getString("memberId")).isEqualTo("s10f-m-pau");
        // The dispatcher's own deliveries (in any order) change nothing more.
        dispatch();
        assertThat(task(id).getString("memberId")).isEqualTo("s10f-m-pau"); assertThat(row(id).getString("memberId")).isEqualTo("s10f-m-pau");
        assertThat(mongo.findById(noteRow, Document.class, "followup_items").getString("memberId")).isEqualTo("s10f-m-pau");
        assertThat(call(HttpMethod.GET, "/tasks/" + id, null, as("pau"), 200).path("id").asText()).isEqualTo(id);
        assertThat(code(call(HttpMethod.GET, "/tasks/" + id, null, as("laura"), 404))).isEqualTo("NOT_FOUND");
    }

    /**
     * Review #3 (R-10-10): a task for Joan's dog, the dog transferred to Laura, the task edited, and only then the delayed
     * `TaskCreated` delivered: no N-20 to Joan, and no N-20 carries the new text. Without a transfer, a task edited before
     * the delivery still notifies its owner with the text of the creation.
     */
    @Test void R_10_10_aDelayedTaskCreatedAfterATransferNotifiesNobodyAndNeverTheNewText() throws Exception {
        String first = "Pujar la rampa amb en Joan", edit = "Text nou per a la Laura: la rampa, a poc a poc";
        var id = createTask("estel", "s10f-d-toby", first, null, 201).path("id").asText();
        transfer("s10f-d-toby", "s10f-m-laura");
        call(HttpMethod.PATCH, "/tasks/" + id, Map.of("text", edit, "version", 0), as("estel"), 200);
        dispatch();
        assertThat(notifications("N-20")).as("nobody: the event's owner no longer owns the dog").isEmpty();
        // The same flow without a transfer: N-20 to the owner with the excerpt the event froze, not the edited text.
        var duna = createTask("estel", "s10f-d-duna", first, null, 201).path("id").asText();
        call(HttpMethod.PATCH, "/tasks/" + duna, Map.of("text", edit, "version", 0), as("estel"), 200);
        dispatch();
        assertThat(notifications("N-20")).extracting(n -> n.getString("channel") + " " + n.getString("accountId")).containsExactly("APP s10f-laura", "EMAIL s10f-laura");
        assertThat(notifications("N-20")).allSatisfy(n -> assertThat(n.get("variables", Document.class).getString("task_excerpt")).isEqualTo(first));
        assertThat(notifications("N-20")).noneSatisfy(n -> assertThat(n.getString("accountId")).isEqualTo("s10f-joan"));
        assertThat(((FakeEmailSender) email).lastTo("s10f-laura@example.test").text()).contains(first).doesNotContain(edit);
        assertThat(((FakeEmailSender) email).messages()).filteredOn(m -> m.to().equalsIgnoreCase("s10f-joan@example.test"))
                .noneSatisfy(m -> assertThat(m.text()).contains("rampa"));
    }

    /**
     * Review #4 (S10 §3): Joan writes the note of Toby and the dog moves to Laura. Whether the note's event is consumed before
     * the transfer (Toby) or only after it (Nit), the D14 row still reads Joan, with Joan's account and gender; the row's
     * member is Laura, the dog's owner.
     */
    @Test void T_10_18_theAuthorOfANoteRowStaysWhoWroteItAcrossATransfer() throws Exception {
        dog("s10f-d-nit", "s10f-m-joan", "Nit", "ACTIVE");
        call(HttpMethod.PUT, "/me/dogs/s10f-d-toby/instructor-note", Map.of("text", "En Toby s'espanta dels túnels"), as("joan"), 200);
        dispatch();
        transfer("s10f-d-toby", "s10f-m-laura");
        dispatch();
        call(HttpMethod.PUT, "/me/dogs/s10f-d-nit/instructor-note", Map.of("text", "La Nit no vol saltar"), as("joan"), 200);
        transfer("s10f-d-nit", "s10f-m-laura");
        dispatch();
        // Soft: every wrong field of both rows is reported (the stored row, then the page D14 reads).
        var soft = new org.assertj.core.api.SoftAssertions();
        for (String dogId : List.of("s10f-d-toby", "s10f-d-nit")) {
            var row = mongo.findById(com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository.noteRowId(CLUB, dogId), Document.class, "followup_items");
            soft.assertThat(row.getString("authorAccountId")).as(dogId + " authorAccountId").isEqualTo("s10f-joan");
            soft.assertThat(row.getString("authorName")).as(dogId + " authorName").isEqualTo("Joan");
            soft.assertThat(row.getString("authorGender")).as(dogId + " authorGender").isEqualTo("MALE");
            soft.assertThat(row.getString("memberId")).as(dogId + " memberId").isEqualTo("s10f-m-laura");
        }
        var items = followup("estel", "filter", "kind:eq:MEMBER_NOTE").path("items");
        soft.assertThat(items).hasSize(2);
        for (var item : items) {
            String dogName = item.path("dogName").asText();
            soft.assertThat(item.path("authorName").asText()).as(dogName + " GET authorName").isEqualTo("Joan");
            soft.assertThat(item.path("authorGender").asText()).as(dogName + " GET authorGender").isEqualTo("MALE");
            soft.assertThat(item.path("memberName").asText()).as(dogName + " GET memberName").isEqualTo("Laura Example");
        }
        soft.assertAll();
    }

    /** Review #5 (S10 §3): D14 pages hold at most 50 rows; 51, 200 and 1000 are the list engine's 400 INVALID_FILTER. */
    @Test void T_10_21_d14PagesHoldAtMost50Rows() throws Exception {
        createTask("estel", "s10f-d-duna", "Tasca de l'Estel", null, 201);
        for (String size : List.of("20", "50")) { assertThat(followup("marc", "size", size).path("size").asInt()).isEqualTo(Integer.parseInt(size)); }
        assertThat(followup("marc").path("size").asInt()).as("the default page").isEqualTo(50);
        for (String size : List.of("51", "200", "1000")) {
            var refused = call(HttpMethod.GET, "/followup?size=" + size, null, as("marc"), 400);
            assertThat(code(refused)).as(size).isEqualTo("INVALID_FILTER");
        }
        // Another universal list keeps the four sizes.
        assertThat(call(HttpMethod.GET, "/attendances?size=200", null, as("admin"), 200).path("size").asInt()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- the port of the sheet and the card, TASKS off (T-10-33)

    @Test void T_10_14_T_10_33_theCardGetsItsThreeBlocksWithTasksOnAndNoneWithTasksOffAndNothingIsQueued() throws Exception {
        var done = createTask("estel", "s10f-d-duna", "Feta", null, 201).path("id").asText();
        clock.setInstant(NOW.plusSeconds(60)); createTask("marc", "s10f-d-duna", "Pendent 1", null, 201);
        clock.setInstant(NOW.plusSeconds(120)); var latest = createTask("marc", "s10f-d-duna", "Pendent 2", null, 201).path("id").asText();
        var deleted = createTask("marc", "s10f-d-duna", "Esborrada", null, 201).path("id").asText();
        call(HttpMethod.DELETE, "/tasks/" + deleted, null, as("marc"), 204, key());
        call(HttpMethod.POST, "/tasks/" + done + "/completion", null, as("laura"), 200);
        call(HttpMethod.PUT, "/me/dogs/s10f-d-duna/instructor-note", Map.of("text", "Nota de la Laura"), as("laura"), 200);
        String file = upload(as("laura"), "INSTRUCTOR_NOTE", "foto.pdf", "application/pdf", "%PDF fictional".getBytes());
        call(HttpMethod.POST, "/attachments", Map.of("entityType", "INSTRUCTOR_NOTE", "entityId", "s10f-d-duna", "fileKey", file, "name", "foto.pdf"), as("laura"), 201);
        var card = call(HttpMethod.GET, "/dogs/s10f-d-duna/instructor-card", null, as("estel"), 200);
        assertThat(card.path("tasks").path("pendingCount").asInt()).isEqualTo(2); assertThat(card.path("tasks").path("doneCount").asInt()).isEqualTo(1);
        assertThat(card.path("tasks").path("latest").path("id").asText()).isEqualTo(latest);
        assertThat(card.path("tasks").path("latest").path("createdByName").asText()).isEqualTo("Marc");
        assertThat(card.path("instructorNote").path("text").asText()).isEqualTo("Nota de la Laura");
        assertThat(card.path("instructorNote").path("attachments")).singleElement().satisfies(a -> assertThat(a.path("url").asText()).contains(file));
        assertThat(card.path("observations").path("version").asLong()).isZero();
        // TASKS off before the outbox runs: no N-20, N-21 or N-22 is ever queued, and the card has none of the three blocks.
        modules(Module.WAITLIST, Module.FAQ, Module.PUSH);
        dispatch();
        assertThat(count("notifications", Criteria.where("code").in("N-20", "N-21", "N-22"))).isZero();
        var minimal = call(HttpMethod.GET, "/dogs/s10f-d-duna/instructor-card", null, as("estel"), 200);
        assertThat(minimal.has("tasks") || minimal.has("instructorNote") || minimal.has("observations")).isFalse();
        for (String path : List.of("/tasks?dogId=s10f-d-duna", "/followup", "/followup/unread-count", "/attachments?entityType=TASK&entityId=" + latest)) {
            assertThat(code(call(HttpMethod.GET, path, null, as("admin"), 404))).as(path).isEqualTo("MODULE_DISABLED");
        }
        assertThat(call(HttpMethod.GET, "/dashboard/counters", null, as("admin"), 200).path("followUpUnread").asInt()).isZero();
    }
}
