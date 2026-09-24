package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.clubs.bookings.application.ports.InMemoryInactivity;
import com.agilityhub.core.clubs.training.application.ports.RingSetupPort;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
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
 * Fictional S09 club (R-09-01…R-09-13 examples): Maria (Rock D, Toby B, Nala E with override false) and Joan (Kira B
 * with override true) share an ACTIVE family group; Pau (Blat), Júlia (Lluna), Sergio (Thai) and twenty crowd members
 * with level-D dogs for the concurrency tests. Rings in catalog order Muntanya, Central, Carretera, Cadells (all
 * reservable) and Petita (not reservable). Now = Monday 05-10-2026 08:00 Europe/Madrid, window 05…08-10, holiday 12-10.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TrainingFixtures.Ports.class)
abstract class TrainingFixtures extends AbstractIntegrationTest {
    static final String CLUB = "s09-a", OTHER = "s09-b", HOST = "s09-a.example.test", OTHER_HOST = "s09-b.example.test";
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = local("2026-10-05T08:00");
    static final String MUN = "s09-r-mun", CEN = "s09-r-cen", CAR = "s09-r-car", CAD = "s09-r-cad", PET = "s09-r-pet";
    static final List<String> DATA = List.of("training_bookings", "bookings", "ring_blocks", "class_sessions", "weeks", "members", "dogs", "family_groups", "memberships",
            "levels", "rings", "instructors", "parameters", "domain_events", "notifications", "audit_entries", "idempotency_records", "impersonation_sessions",
            "export_jobs", "catalog_write_locks", "census_write_locks", "ring_slot_locks");
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts; @Autowired OutboxDispatcher dispatcher;
    @Autowired InMemoryInactivity inactivity; @Autowired TransactionTemplate tx; @Autowired EventPublisher events; @Autowired TestRingSetups setups;
    @Autowired com.agilityhub.core.clubs.training.application.TrainingGridCache cache;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;

    static Instant local(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }

    @TestConfiguration(proxyBeanMethods = false)
    static class Ports { @Bean TestRingSetups trainingTestRingSetups() { return new TestRingSetups(); } }
    /** S16 stand-in: the mounted setups the grid shows with COURSES (R-09-15). */
    static class TestRingSetups implements RingSetupPort {
        final Map<String, Setup> setups = new ConcurrentHashMap<>();
        @Override public Optional<Setup> active(String setupId, Locale locale) { return Optional.ofNullable(setups.get(setupId)); }
    }

    @BeforeEach void fixtures() {
        clock.setInstant(NOW); inactivity.clear(); setups.setups.clear();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(Query.query(Criteria.where("_id").regex("^s09-")), "accounts");
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        club(CLUB, HOST, List.of(Module.values())); club(OTHER, OTHER_HOST, List.of(Module.values())); hosts.invalidate();
        level("B", false, 1); level("D", true, 2); level("E", true, 3);
        ring(MUN, "Muntanya", "MUN", 1, true, null); ring(CEN, "Central", "CEN", 2, true, null); ring(CAR, "Carretera", "CAR", 3, true, null);
        ring(CAD, "Cadells", "CAD", 4, true, null); ring(PET, "Petita", "PET", 5, false, null);
        mongo.save(new Document("_id", "s09-instructor").append("clubId", CLUB).append("memberId", "s09-m-estel").append("shortName", "Estel").append("color", "#123456")
                .append("active", true).append("version", 0), "instructors");
        parameter("levels.enabled", true); parameter("club.holidays", List.of(Map.of("date", "2026-10-12", "label", "Festa fictícia")));
        cache.invalidateClub(CLUB); cache.invalidateClub(OTHER);
        account("maria", "MEMBER", "s09-m-maria"); member("s09-m-maria", "maria", "Maria", "ca");
        dog("s09-d-rock", "s09-m-maria", "Rock", "D", null); dog("s09-d-toby", "s09-m-maria", "Toby", "B", null); dog("s09-d-nala", "s09-m-maria", "Nala", "E", false);
        account("joan", "MEMBER", "s09-m-joan"); member("s09-m-joan", "joan", "Joan", "es"); dog("s09-d-kira", "s09-m-joan", "Kira", "B", true);
        account("pau", "MEMBER", "s09-m-pau"); member("s09-m-pau", "pau", "Pau", "ca"); dog("s09-d-blat", "s09-m-pau", "Blat", "D", null);
        account("julia", "MEMBER", "s09-m-julia"); member("s09-m-julia", "julia", "Júlia", "en"); dog("s09-d-lluna", "s09-m-julia", "Lluna", "D", null);
        account("sergio", "MEMBER", "s09-m-sergio"); member("s09-m-sergio", "sergio", "Sergio", "es"); dog("s09-d-thai", "s09-m-sergio", "Thai", "D", null);
        account("admin", "ADMIN", "s09-m-admin"); member("s09-m-admin", "admin", "Admin", "ca");
        account("estel", "INSTRUCTOR", "s09-m-estel"); member("s09-m-estel", "estel", "Estel", "ca");
        for (int i = 0; i < 20; i++) { account("c" + i, "MEMBER", "s09-m-c" + i); member("s09-m-c" + i, "c" + i, "Crowd" + i, "ca"); dog("s09-d-c" + i, "s09-m-c" + i, "Dog" + i, "D", null); }
        mongo.save(new Document("_id", "s09-group").append("clubId", CLUB).append("holderMemberId", "s09-m-maria").append("memberIds", List.of("s09-m-maria", "s09-m-joan"))
                .append("status", "ACTIVE").append("version", 0), "family_groups");
        for (String m : List.of("s09-m-maria", "s09-m-joan")) { mongo.updateFirst(Query.query(Criteria.where("_id").is(m)), new Update().set("familyGroupId", "s09-group"), "members"); }
    }
    void club(String id, String host, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(id)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, host));
        tree.set("modules", mapper.valueToTree(modules)); tree.put("timeZone", "Europe/Madrid"); tree.set("locales", mapper.valueToTree(List.of("ca", "es", "en")));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
    }
    void modules(Module... modules) { club(CLUB, HOST, List.of(modules)); }
    void timeZone(String zone) {
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); tree.put("timeZone", zone); clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
    }
    void parameter(String key, Object value) {
        mongo.getCollection("parameters").deleteMany(new Document("clubId", CLUB).append("key", key));
        mongo.insert(new Parameter(UUID.randomUUID().toString(), CLUB, key, value, "unknown", "club", null, List.of(), 0L, clock.instant()));
        configs.invalidate(CLUB);
    }
    void level(String code, boolean grants, int order) {
        mongo.save(new Document("_id", "s09-lv-" + code).append("clubId", CLUB).append("code", code).append("nameKeys", List.of(code.toLowerCase()))
                .append("name", new Document("values", new Document("ca", code).append("es", code).append("en", code)).append("defaultLocale", "ca"))
                .append("active", true).append("order", order).append("capacity", 5).append("grantsFreeTraining", grants).append("version", 0), "levels");
    }
    void ring(String id, String name, String shortName, int order, boolean training, Integer capacity) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("name", name).append("shortName", shortName).append("color", "#8FCE8F")
                .append("allowsFreeTraining", training).append("trainingCapacity", capacity).append("active", true).append("order", order).append("version", 0), "rings");
    }
    void account(String id, String role, String memberId) {
        String account = "s09-" + id;
        mongo.save(new com.agilityhub.core.identity.persistence.Account(account, account + "@example.test", "Example " + id, "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(account, account, CLUB, memberId, Set.of(com.agilityhub.core.identity.domain.Role.valueOf(role)),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.valueOf(role)));
    }
    void member(String id, String account, String firstName, String locale) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("accountId", "s09-" + account).append("firstName", firstName).append("lastName1", "Example")
                .append("memberNumber", Math.abs(id.hashCode() % 10000)).append("status", "ACTIVE").append("bookingBlock", new Document("active", false))
                .append("signup", new Document("locale", locale)).append("contactEmails", List.of(new Document("email", "s09-" + account + "@example.test")))
                .append("phones", List.of(new Document("prefix", "+34").append("number", "600000009"))).append("version", 0), "members");
    }
    void dog(String id, String memberId, String name, String level, Boolean override) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", memberId).append("name", name).append("sex", "MALE").append("status", "ACTIVE")
                .append("levelId", "s09-lv-" + level).append("freeTrainingOverride", override).append("version", 0), "dogs");
    }
    void classSession(String id, String ringId, String start, String end, String state) {
        var starts = local(start); var ends = local(end); var s = new LinkedHashMap<String, Object>();
        s.put("id", id); s.put("clubId", CLUB); s.put("weekId", "s09-week"); s.put("date", start.substring(0, 10)); s.put("startTime", start.substring(11));
        s.put("endTime", end.substring(11)); s.put("startsAt", starts.toString()); s.put("endsAt", ends.toString()); s.put("ringId", ringId); s.put("state", state);
        s.put("levelIds", List.of()); s.put("instructorIds", List.of("s09-instructor")); s.put("capacity", 5); s.put("capacityMode", "MANUAL"); s.put("description", "Classe " + id);
        s.put("counters", Map.of("booked", 0, "waiting", 0)); s.put("risk", Map.of("exempt", false, "notifiedBookingIds", List.of())); s.put("version", 0);
        mongo.insert(mapper.convertValue(s, com.agilityhub.core.clubs.scheduling.persistence.ClassSession.class));
    }
    void block(String id, String ringId, String from, String to, String kind, String reason, String note, String activityId) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("ringId", ringId).append("from", Date.from(local(from))).append("to", Date.from(local(to)))
                .append("kind", kind).append("reason", reason).append("note", note).append("activityId", activityId).append("state", "ACTIVE").append("version", 0L)
                .append("createdAt", Date.from(NOW)).append("createdByAccountId", "s09-estel"), "ring_blocks");
    }

    RequestPostProcessor as(String account) {
        var membership = mongo.findOne(Query.query(Criteria.where("_id").is("s09-" + account)), Document.class, "memberships");
        String member = membership.getString("memberId"), role = membership.getList("roles", String.class).getFirst();
        return jwt().jwt(j -> j.subject("s09-" + account).claim("clubId", CLUB).claim("memberId", member).claim("name", "Example " + account))
                .authorities(() -> "ROLE_" + role);
    }
    RequestPostProcessor impersonating(String admin, String memberId) {
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var tenant = TenantContext.open(CLUB)) { issued = impersonations.create("s09-" + admin, memberId, "Fictional S09 training support"); }
        return jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER");
    }
    JsonNode call(HttpMethod method, String path, Object body, RequestPostProcessor auth, int expected) throws Exception { return call(method, path, body, auth, expected, null); }
    JsonNode call(HttpMethod method, String path, Object body, RequestPostProcessor auth, int expected, String key) throws Exception {
        MockHttpServletRequestBuilder request = request(method, "/api/v1" + path).header("Host", HOST);
        if (auth != null) { request.with(auth); }
        if (body != null) { request.contentType("application/json").content(mapper.writeValueAsBytes(body)); }
        if (key != null) { request.header("Idempotency-Key", key); }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(method + " " + path + " " + response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsByteArray().length == 0 || response.getContentType() == null || !response.getContentType().contains("json")
                ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    /** POST /training-bookings with a fresh Idempotency-Key; `ringId` null = «Qualsevol». */
    JsonNode book(RequestPostProcessor auth, String dogId, String localStart, String ringId, int expected) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("dogId", dogId); body.put("startsAt", local(localStart).toString()); if (ringId != null) { body.put("ringId", ringId); }
        return call(HttpMethod.POST, "/training-bookings", body, auth, expected, UUID.randomUUID().toString());
    }
    JsonNode cancel(RequestPostProcessor auth, String bookingId, String reason, int expected) throws Exception {
        return call(HttpMethod.POST, "/training-bookings/" + bookingId + "/cancellation", reason == null ? Map.of() : Map.of("reason", reason), auth, expected);
    }
    JsonNode slots(RequestPostProcessor auth, String from, String to, String dogId) throws Exception {
        return call(HttpMethod.GET, "/training-slots?from=" + from + "&to=" + to + (dogId == null ? "" : "&dogId=" + dogId), null, auth, 200);
    }
    /** The cell of a ring at a local slot start in a /training-slots answer. */
    static JsonNode cell(JsonNode grid, String localStart, String ringId) {
        String date = localStart.substring(0, 10), startsAt = local(localStart).toString();
        for (var day : grid.path("days")) {
            if (!day.path("date").asText().equals(date)) { continue; }
            for (var slot : day.path("slots")) { if (slot.path("startsAt").asText().equals(startsAt)) { return slot.path("rings").path(ringId); } }
        }
        throw new AssertionError("no slot " + localStart);
    }
    static JsonNode slot(JsonNode grid, String localStart) {
        for (var day : grid.path("days")) for (var slot : day.path("slots")) { if (slot.path("startsAt").asText().equals(local(localStart).toString())) { return slot; } }
        throw new AssertionError("no slot " + localStart);
    }
    long count(String collection, Criteria criteria) { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB)).addCriteria(criteria), collection); }
    List<Document> eventsOf(String type) { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type)), Document.class, "domain_events"); }
    Document training(String id) { return mongo.findById(id, Document.class, "training_bookings"); }
    void dispatch() { for (int i = 0; i < 6; i++) { dispatcher.dispatch(); } }
    void publish(com.agilityhub.core.shared.domain.DomainEvent event) {
        try (var tenant = TenantContext.open(CLUB)) { tx.executeWithoutResult(status -> events.publish(event)); }
    }
    String code(JsonNode error) { return error.path("code").asText(); }
    List<Document> notifications(String code) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), Document.class, "notifications");
    }
}
