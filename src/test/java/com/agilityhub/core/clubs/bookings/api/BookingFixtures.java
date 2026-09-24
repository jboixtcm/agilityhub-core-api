package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.ports.*;
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
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
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
 * Fictional S08 club: Laura (Duna C, Rock D) and Joan (Toby C) share an ACTIVE family group, Pere is another member,
 * twenty crowd members for the concurrency tests; classes around Tuesday 06-10-2026 10:00 Europe/Madrid, when
 * W0 = [Sun 04-10 20:00, Sun 11-10 20:00) and W1 = the next week (R-08-01 example).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
abstract class BookingFixtures extends AbstractIntegrationTest {
    static final String CLUB = "s08-a", OTHER = "s08-b", HOST = "s08-a.example.test", OTHER_HOST = "s08-b.example.test";
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = local("2026-10-06T10:00");
    static final List<String> DATA = List.of("bookings", "seat_holds", "seat_locks", "waitlist_entries", "class_sessions", "members", "dogs", "family_groups",
            "memberships", "accounts", "levels", "rings", "instructors", "parameters", "domain_events", "notifications", "audit_entries", "idempotency_records",
            "plans", "prices", "upfront_payments", "checkout_sessions", "impersonation_sessions");
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts; @Autowired OutboxDispatcher dispatcher;
    @Autowired InMemoryPackBalances packs; @Autowired InMemoryInactivity inactivity; @Autowired TransactionTemplate tx; @Autowired EventPublisher events;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;

    static Instant local(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }

    @BeforeEach void fixtures() {
        clock.setInstant(NOW); packs.clear(); inactivity.clear();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(Query.query(Criteria.where("_id").regex("^s08-")), "accounts");
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        club(CLUB, HOST, List.of(Module.values())); club(OTHER, OTHER_HOST, List.of(Module.values())); hosts.invalidate();
        for (String level : List.of("C", "D")) {
            mongo.save(new Document("_id", "s08-lv-" + level).append("clubId", CLUB).append("code", level).append("nameKeys", List.of(level.toLowerCase()))
                    .append("name", new Document("values", new Document("ca", level).append("es", level).append("en", level)).append("defaultLocale", "ca"))
                    .append("active", true).append("order", level.equals("C") ? 1 : 2).append("capacity", 5).append("grantsFreeTraining", false).append("version", 0), "levels");
        }
        mongo.save(new Document("_id", "s08-ring").append("clubId", CLUB).append("name", "Central").append("shortName", "CEN").append("color", "#8FCE8F")
                .append("allowsFreeTraining", true).append("active", true).append("order", 1).append("version", 0), "rings");
        mongo.save(new Document("_id", "s08-instructor").append("clubId", CLUB).append("memberId", "s08-m-inst").append("shortName", "Estela").append("color", "#123456")
                .append("active", true).append("version", 0), "instructors");
        parameter("levels.enabled", true);
        account("laura", "MEMBER", "s08-m-laura"); member("s08-m-laura", "laura", "Laura", "ca"); dog("s08-d-duna", "s08-m-laura", "Duna", "C", "FEMALE");
        dog("s08-d-rock", "s08-m-laura", "Rock", "D", "MALE");
        account("joan", "MEMBER", "s08-m-joan"); member("s08-m-joan", "joan", "Joan", "es"); dog("s08-d-toby", "s08-m-joan", "Toby", "C", "MALE");
        account("pere", "MEMBER", "s08-m-pere"); member("s08-m-pere", "pere", "Pere", "en"); dog("s08-d-nit", "s08-m-pere", "Nit", "C", "FEMALE");
        account("admin", "ADMIN", "s08-m-admin"); member("s08-m-admin", "admin", "Admin", "ca");
        account("inst", "INSTRUCTOR", "s08-m-inst"); member("s08-m-inst", "inst", "Estela", "ca");
        for (int i = 0; i < 20; i++) { account("c" + i, "MEMBER", "s08-m-c" + i); member("s08-m-c" + i, "c" + i, "Crowd" + i, "ca"); dog("s08-d-c" + i, "s08-m-c" + i, "Dog" + i, "C", "MALE"); }
        mongo.save(new Document("_id", "s08-group").append("clubId", CLUB).append("holderMemberId", "s08-m-laura").append("memberIds", List.of("s08-m-laura", "s08-m-joan"))
                .append("status", "ACTIVE").append("version", 0), "family_groups");
        for (String m : List.of("s08-m-laura", "s08-m-joan")) { mongo.updateFirst(Query.query(Criteria.where("_id").is(m)), new org.springframework.data.mongodb.core.query.Update().set("familyGroupId", "s08-group"), "members"); }
        session("wed", "2026-10-07T18:50", 3, List.of()); session("thu", "2026-10-08T18:50", 3, List.of()); session("fri", "2026-10-09T20:00", 3, List.of());
        session("sat", "2026-10-10T09:00", 3, List.of()); session("mon", "2026-10-12T18:50", 3, List.of()); session("mon2", "2026-10-13T18:50", 3, List.of());
        session("later", "2026-10-20T18:50", 3, List.of()); session("last", "2026-10-08T20:00", 1, List.of()); session("levelD", "2026-10-08T17:40", 3, List.of("s08-lv-D"));
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
    void account(String id, String role, String memberId) {
        String account = "s08-" + id;
        mongo.save(new com.agilityhub.core.identity.persistence.Account(account, account + "@example.test", "Example " + id, "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(account, account, CLUB, memberId, Set.of(com.agilityhub.core.identity.domain.Role.valueOf(role)),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.valueOf(role)));
    }
    void member(String id, String account, String firstName, String locale) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("accountId", "s08-" + account).append("firstName", firstName).append("lastName1", "Example")
                .append("memberNumber", Math.abs(id.hashCode() % 10000)).append("status", "ACTIVE").append("bookingBlock", new Document("active", false))
                .append("signup", new Document("locale", locale)).append("contactEmails", List.of(new Document("email", "s08-" + account + "@example.test")))
                .append("phones", List.of(new Document("prefix", "+34").append("number", "600000001"))).append("version", 0), "members");
    }
    void dog(String id, String memberId, String name, String level, String sex) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", memberId).append("name", name).append("sex", sex).append("status", "ACTIVE")
                .append("levelId", "s08-lv-" + level).append("version", 0), "dogs");
    }
    void session(String id, String start, int capacity, List<String> levels) {
        var starts = local(start); var date = LocalDateTime.parse(start).toLocalDate(); var s = new LinkedHashMap<String, Object>();
        s.put("id", "s08-" + id); s.put("clubId", CLUB); s.put("weekId", "s08-week"); s.put("date", date.toString()); s.put("startTime", start.substring(11));
        s.put("endTime", LocalDateTime.parse(start).plusHours(1).toLocalTime().toString()); s.put("startsAt", starts.toString()); s.put("endsAt", starts.plusSeconds(3600).toString());
        s.put("ringId", "s08-ring"); s.put("state", "ACTIVE"); s.put("levelIds", levels); s.put("instructorIds", List.of("s08-instructor")); s.put("capacity", capacity);
        s.put("capacityMode", "MANUAL"); s.put("description", "Classe " + id); s.put("counters", Map.of("booked", 0, "waiting", 0));
        s.put("risk", Map.of("exempt", false, "notifiedBookingIds", List.of())); s.put("version", 0);
        mongo.insert(mapper.convertValue(s, com.agilityhub.core.clubs.scheduling.persistence.ClassSession.class));
    }

    RequestPostProcessor as(String account) {
        var membership = mongo.findOne(Query.query(Criteria.where("_id").is("s08-" + account)), Document.class, "memberships");
        String member = membership.getString("memberId"), role = membership.getList("roles", String.class).getFirst();
        return jwt().jwt(j -> j.subject("s08-" + account).claim("clubId", CLUB).claim("memberId", member).claim("name", "Example " + account))
                .authorities(() -> "ROLE_" + role);
    }
    RequestPostProcessor impersonating(String admin, String memberId) {
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var tenant = TenantContext.open(CLUB)) { issued = impersonations.create("s08-" + admin, memberId, "Fictional S08 booking support"); }
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
        return response.getContentAsByteArray().length == 0 || !response.getContentType().contains("json") ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    JsonNode hold(RequestPostProcessor auth, String classId, String dogId, int expected) throws Exception {
        return call(HttpMethod.POST, "/seat-holds", Map.of("classSessionId", "s08-" + classId, "dogId", dogId), auth, expected);
    }
    JsonNode confirm(RequestPostProcessor auth, String holdId, String swapId, int expected) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("seatHoldId", holdId); if (swapId != null) { body.put("swapBookingId", swapId); }
        return call(HttpMethod.POST, "/bookings", body, auth, expected, UUID.randomUUID().toString());
    }
    /** Hold + confirm; returns the booking. */
    JsonNode book(RequestPostProcessor auth, String classId, String dogId) throws Exception {
        return confirm(auth, hold(auth, classId, dogId, 201).path("id").asText(), null, 201);
    }
    JsonNode cancel(RequestPostProcessor auth, String bookingId, int expected) throws Exception {
        return call(HttpMethod.POST, "/bookings/" + bookingId + "/cancellation", Map.of(), auth, expected);
    }
    JsonNode join(RequestPostProcessor auth, String classId, String dogId, int expected) throws Exception {
        return call(HttpMethod.POST, "/waitlist-entries", Map.of("classSessionId", "s08-" + classId, "dogId", dogId), auth, expected);
    }
    /** [AGAFA LA PLAÇA]: the seat hold of an offered entry. */
    JsonNode holdFor(RequestPostProcessor auth, String classId, String dogId, String entryId, int expected) throws Exception {
        return call(HttpMethod.POST, "/seat-holds", Map.of("classSessionId", "s08-" + classId, "dogId", dogId, "waitlistEntryId", entryId), auth, expected);
    }
    JsonNode claim(RequestPostProcessor auth, String entryId, String holdId, String swapId, int expected, String key) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("seatHoldId", holdId); if (swapId != null) { body.put("swapBookingId", swapId); }
        return call(HttpMethod.POST, "/waitlist-entries/" + entryId + "/claim", body, auth, expected, key);
    }
    JsonNode claim(RequestPostProcessor auth, String entryId, String holdId, String swapId, int expected) throws Exception {
        return claim(auth, entryId, holdId, swapId, expected, UUID.randomUUID().toString());
    }
    Document entry(String id) { return mongo.findById(id, Document.class, "waitlist_entries"); }
    long count(String collection, Criteria criteria) { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB)).addCriteria(criteria), collection); }
    long events(String type) { return count("domain_events", Criteria.where("type").is(type)); }
    List<Document> eventsOf(String type) { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type)), Document.class, "domain_events"); }
    Document booking(String id) { return mongo.findById(id, Document.class, "bookings"); }
    Document session(String id) { return mongo.findById("s08-" + id, Document.class, "class_sessions"); }
    void dispatch() { for (int i = 0; i < 6; i++) { dispatcher.dispatch(); } }
    void publish(com.agilityhub.core.shared.domain.DomainEvent event) {
        try (var tenant = TenantContext.open(CLUB)) { tx.executeWithoutResult(status -> events.publish(event)); }
    }
    String code(JsonNode error) { return error.path("code").asText(); }
    /** R-08-18: Laura and Pere on a fictional SINGLE_CLASS plan that pays to book (12,00 €). */
    void payToBook() {
        mongo.save(new Document("_id", "s08-plan").append("clubId", CLUB).append("type", "SINGLE_CLASS").append("singleClass", new Document("chargeMode", "PAY_TO_BOOK")), "plans");
        mongo.save(new Document("_id", "s08-price").append("clubId", CLUB).append("planId", "s08-plan").append("amount", new Document("amountMinor", 1200L).append("currency", "EUR")), "prices");
        mongo.updateMulti(Query.query(Criteria.where("_id").in("s08-m-laura", "s08-m-pere")),
                new org.springframework.data.mongodb.core.query.Update().set("planId", "s08-plan").set("priceId", "s08-price"), "members");
    }
    String checkoutSession(String bookingId) { return booking(bookingId).get("charge", Document.class).getString("checkoutSessionId"); }
    Document line(String bookingId) { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("bookingId").is(bookingId)), Document.class, "upfront_payments"); }
    void openPack(String memberId, String dogId, int total, int consumed, LocalDate expiresOn) {
        try (var tenant = TenantContext.open(CLUB)) { packs.open(memberId, dogId, total, consumed, expiresOn); }
    }
}
