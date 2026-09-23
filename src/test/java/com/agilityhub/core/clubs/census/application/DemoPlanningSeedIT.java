package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.support.DemoFixtures;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E4-T05: the demo seed holds every planning/activity state the E4 gate and T-06-28/T-07-32 (back half) need. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoPlanningSeedIT extends AbstractIntegrationTest {
    static final List<String> SEEDED = List.of("week_templates", "weeks", "class_sessions", "ring_blocks", "bookings", "waitlist_entries", "activities", "activity_registrations");
    static final LocalDate MONDAY = LocalDate.parse("2026-09-07");
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand command; @Autowired DemoPlanningService planning;
    @Autowired HostTenantResolver hosts; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired MockMvc mvc;
    @Autowired IcuMessageSource messages;
    String club;
    @BeforeEach void clear() {
        for (String collection : mongo.getCollectionNames()) { if (!collection.startsWith("system.")) { mongo.remove(new Query(), collection); } }
        hosts.invalidate(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        club = definitions.apply(codec.read(Path.of("seeds/club-canic.yaml")), false).id();
    }
    Map<String, List<Document>> snapshot() {
        var result = new TreeMap<String, List<Document>>();
        for (String name : mongo.getCollectionNames()) { result.put(name, mongo.findAll(Document.class, name)); } return result;
    }
    void seed(String... extra) throws Exception {
        var args = new ArrayList<>(List.of("--club=canic", "--seed=42")); args.addAll(List.of(extra));
        command.run(new DefaultApplicationArguments(args.toArray(String[]::new)));
    }
    JsonNode admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, "ADMIN", 200); }
    JsonNode call(MockHttpServletRequestBuilder request, String role, int expected) throws Exception {
        var body = mvc.perform(request.header("Host", "app.agilitycanic.cat").with(jwt().jwt(j -> j.subject("demo-" + role.toLowerCase()).claim("clubId", club))
                .authorities(() -> "ROLE_" + role))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : mapper.readTree(body);
    }
    Document one(String collection, Criteria criteria) { return mongo.findOne(Query.query(criteria), Document.class, collection); }
    Document session(LocalDate date, String start, String ring) {
        return one("class_sessions", Criteria.where("date").is(date.toString()).and("startTime").is(start).and("ringId").is(ring));
    }
    String ring(String shortName) { return one("rings", Criteria.where("shortName").is(shortName)).getString("_id"); }
    Document activity(String title) { return one("activities", Criteria.where("title.values.ca").is(title)); }

    @Test void T_06_28_T_07_32_demoSeedHoldsThePlanningAndActivityStatesOfTheGate() throws Exception {
        seed();
        var templates = admin(get("/api/v1/week-templates")).path("items");
        assertThat(templates).hasSize(3);
        var byName = new HashMap<String, JsonNode>(); templates.forEach(t -> byName.put(t.path("name").asText(), t));
        assertThat(byName.get("Setmana A").path("inconsistencyCount").asInt()).isZero();
        assertThat(byName.get("Setmana B").path("inconsistencyCount").asInt()).isEqualTo(1);
        assertThat(byName.get("Dissabtes").path("inconsistencyCount").asInt()).isZero();
        var b = admin(get("/api/v1/week-templates/" + byName.get("Setmana B").path("id").asText()));
        assertThat(b.path("canGenerate").asBoolean()).isFalse(); assertThat(b.at("/inconsistencies/0/type").asText()).isEqualTo("RING_DOUBLE_BOOKED");

        var draft = one("weeks", Criteria.where("startDate").is(MONDAY.plusWeeks(1).toString()));
        var validated = one("weeks", Criteria.where("startDate").is(MONDAY.plusWeeks(2).toString()));
        assertThat(draft.getString("state")).isEqualTo("GENERATED"); assertThat(validated.getString("state")).isEqualTo("VALIDATED");
        assertThat(mongo.count(Query.query(Criteria.where("weekId").is(draft.getString("_id")).and("state").ne("DRAFT")), "class_sessions")).isZero();
        var draftCalendar = admin(get("/api/v1/weeks/" + draft.getString("_id") + "/calendar").param("filter", "DRAFT"));
        assertThat(draftCalendar.path("draftCount").asInt()).isEqualTo(38); assertThat(draftCalendar.path("canValidate").asBoolean()).isTrue();
        var calendar = admin(get("/api/v1/weeks/" + validated.getString("_id") + "/calendar"));
        assertThat(calendar.path("inconsistencies")).extracting(i -> i.path("type").asText()).containsExactly("INSTRUCTOR_DOUBLE_BOOKED");
        assertThat(calendar.path("ringBlocks")).anySatisfy(block -> assertThat(block.path("note").asText()).isEqualTo("manteniment"));
        var candidates = admin(get("/api/v1/weeks/generation-candidates")).path("items");
        assertThat(candidates).filteredOn(c -> c.path("proposed").asBoolean()).extracting(c -> c.path("startDate").asText()).containsExactly(MONDAY.plusWeeks(3).toString());

        var wednesday = MONDAY.plusWeeks(2).plusDays(2);
        var target = session(wednesday, "18:50", ring("CEN"));
        assertThat(target.get("counters", Document.class)).containsEntry("booked", 4).containsEntry("waiting", 2);
        assertThat(mongo.count(Query.query(Criteria.where("classSessionId").is(target.getString("_id")).and("packMovementId").ne(null)), "bookings")).isEqualTo(2);
        // E5-T02: the registrants are real S08 bookings made by each member through hold + confirmation.
        assertThat(mongo.count(Query.query(Criteria.where("origin").ne("APP")), "bookings")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("type").is("BookingCreated")), "domain_events")).isEqualTo(mongo.count(new Query(), "bookings"));
        var risk = mongo.findOne(Query.query(Criteria.where("date").is(wednesday.toString()).and("startTime").is("09:30").and("ringId").is(ring("CAD"))), Document.class, "class_sessions");
        assertThat(risk.getString("state")).isEqualTo("CANCELLED");
        assertThat(risk.get("cancellation", Document.class).getString("reason")).isEqualTo("RISK_REVIEW");
        assertThat(risk.get("cancellation", Document.class).getString("adminText")).isEqualTo(messages.format("scheduling.autoCancel.text", Map.of(), Locale.forLanguageTag("ca")));
        var full = session(wednesday, "08:30", ring("CEN")).get("counters", Document.class);
        assertThat(full).containsEntry("booked", 5).containsEntry("waiting", 1);
        var saturday = mongo.findOne(Query.query(Criteria.where("date").is(MONDAY.plusWeeks(2).plusDays(5).toString()).and("startTime").is("18:30")), Document.class, "class_sessions");
        assertThat(saturday.getString("state")).isEqualTo("CANCELLED");
        assertThat(saturday.get("cancellation", Document.class)).containsEntry("reason", "ACTIVITY").containsEntry("affectedBookings", 2);
        assertThat(mongo.count(Query.query(Criteria.where("type").is("ClassCancelledByClub").and("aggregateId").is(saturday.getString("_id"))), "domain_events")).isEqualTo(1);
        var maintenance = one("ring_blocks", Criteria.where("reason").is("MAINTENANCE"));
        assertThat(maintenance.getString("ringId")).isEqualTo(ring("CAR"));
        assertThat(maintenance.getDate("from").toInstant()).isEqualTo(wednesday.atTime(16, 0).atZone(ZoneId.of("Europe/Madrid")).toInstant());

        var torneig = activity("Torneig d'Estiu 2026"); var seminari = activity("Seminari de handling");
        var lliga = activity("Lliga social — 3a jornada"); var demo = activity("Demostració Festa Major");
        assertThat(List.of(torneig, seminari, lliga, demo)).extracting(a -> a.getString("state")).containsExactly("PUBLISHED", "PUBLISHED", "PUBLISHED", "DRAFT");
        assertThat(torneig.get("counters", Document.class)).containsEntry("active", 22).containsEntry("waiting", 0);
        assertThat(torneig.getList("ringBlockIds", String.class)).hasSize(5);
        assertThat(seminari.get("counters", Document.class)).containsEntry("active", 12).containsEntry("waiting", 2);
        assertThat(lliga.get("counters", Document.class)).containsEntry("active", 10);
        assertThat(lliga.get("image", Document.class)).isNotNull(); assertThat(lliga.getList("documents", Document.class)).hasSize(1);
        assertThat(demo.get("location", Document.class)).containsEntry("atClub", false);
        assertThat(mongo.count(Query.query(Criteria.where("activityId").is(torneig.getString("_id")).and("reason").is("ACTIVITY").and("state").is("ACTIVE")), "ring_blocks")).isEqualTo(5);
        assertThat(mongo.count(Query.query(Criteria.where("origin").ne("APP")), "activity_registrations")).isZero();

        var logins = new HashSet<String>();
        mongo.find(Query.query(Criteria.where("email").regex("^(admin|canic\\.admin|instructor.*|member.*)@example\\.test$")), Document.class, "accounts")
                .forEach(a -> mongo.find(Query.query(Criteria.where("accountId").is(a.getString("_id"))), Document.class, "members").forEach(m -> logins.add(m.getString("_id"))));
        assertThat(logins).hasSize(15);
        assertThat(mongo.count(Query.query(Criteria.where("memberId").in(logins)), "activity_registrations")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("memberId").in(logins)), "bookings")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("memberId").in(logins)), "waitlist_entries")).isZero();
        for (var booking : mongo.findAll(Document.class, "bookings")) {
            var levels = mongo.findById(booking.getString("classSessionId"), Document.class, "class_sessions").getList("levelIds", String.class);
            assertThat(levels).contains(mongo.findById(booking.getString("dogId"), Document.class, "dogs").getString("levelId"));
        }
        for (String collection : SEEDED) {
            assertThat(mongo.count(new Query(), collection)).as(collection).isPositive();
            assertThat(mongo.count(Query.query(Criteria.where("clubId").ne(club)), collection)).as(collection).isZero();
        }
        assertThat(mongo.count(Query.query(Criteria.where("phones.0.prefix").is("+34")), "members")).isEqualTo(194);
    }

    @Test void T_06_28_demoPlanningIsDeterministicIdempotentAndGuarded() throws Exception {
        seed(); var first = registrants(); var saved = snapshot();
        seed(); seed("--week-start=2026-09-14"); assertThat(snapshot()).isEqualTo(saved);
        clear(); seed(); assertThat(registrants()).isEqualTo(first);
        for (String[] args : List.of(new String[]{"--club=canic", "--week-start=2026-09-08"}, new String[]{"--club=canic", "--week-start=x"},
                new String[]{"--club=canic", "--week-start=2026-09-14", "--week-start=2026-09-21"})) {
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--week-start");
        }
        var spec = DemoFixtures.spec(mapper, false);
        try (var tenant = TenantContext.open(club)) {
            assertThatThrownBy(() -> planning.apply(spec, Map.of("activities", List.of()), 42, MONDAY)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.CLUB_NOT_EMPTY));
            assertThatThrownBy(() -> planning.apply(spec, Map.of(), 42, MONDAY.plusDays(1))).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
        for (String profile : List.of("prod", "staging", "local,prod")) {
            var environment = new org.springframework.mock.env.MockEnvironment(); environment.setActiveProfiles(profile.split(","));
            var restricted = new DemoPlanningService(null, null, List.of(), mapper, environment);
            assertThatThrownBy(() -> restricted.apply(spec, Map.of(), 42, MONDAY)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
    @Test void T_06_28_planningNeedsTheCensusDemoFirst() throws Exception {
        var spec = DemoFixtures.spec(mapper, false);
        try (var tenant = TenantContext.open(club)) {
            assertThatThrownBy(() -> planning.apply(spec, Map.of(), 42, MONDAY)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        assertThat(mongo.count(new Query(), "weeks")).isZero();
    }
    /** Slot or activity → the registrant member numbers, independent of generated and club-derived ids. */
    Map<String, List<String>> registrants() {
        var result = new TreeMap<String, List<String>>(); var rings = new HashMap<String, String>();
        mongo.findAll(Document.class, "rings").forEach(r -> rings.put(r.getString("_id"), r.getString("shortName")));
        var numbers = new HashMap<String, String>(); mongo.findAll(Document.class, "members").forEach(m -> numbers.put(m.getString("_id"), String.valueOf(m.get("memberNumber"))));
        for (String collection : List.of("bookings", "waitlist_entries")) {
            for (var booking : mongo.findAll(Document.class, collection)) {
                var c = mongo.findById(booking.getString("classSessionId"), Document.class, "class_sessions");
                result.computeIfAbsent(c.getString("date") + " " + c.getString("startTime") + " " + rings.get(c.getString("ringId")) + " " + collection + " " + booking.getString("state"), k -> new ArrayList<>()).add(numbers.get(booking.getString("memberId")));
            }
        }
        for (var r : mongo.findAll(Document.class, "activity_registrations")) {
            var a = mongo.findById(r.getString("activityId"), Document.class, "activities");
            result.computeIfAbsent(a.get("title", Document.class).get("values", Document.class).getString("ca") + " " + r.getString("state"), k -> new ArrayList<>()).add(numbers.get(r.getString("memberId")));
        }
        result.values().forEach(Collections::sort); return result;
    }

    @Test void T_06_28_D4cCancellationOfTheSeededClassNotifiesTheDemoRegistrants() throws Exception {
        seed();
        var target = session(MONDAY.plusWeeks(2).plusDays(2), "18:50", ring("CEN")); String id = target.getString("_id");
        var preview = admin(get("/api/v1/class-sessions/" + id + "/cancellation-preview"));
        assertThat(preview.path("bookings")).hasSize(4); assertThat(preview.path("waitlistCount").asInt()).isEqualTo(2);
        assertThat(preview.at("/bookings/0/channels").toString()).contains("APP", "EMAIL", "SMS");
        call(post("/api/v1/class-sessions/" + id + "/cancellation").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content("{\"reason\":\"CLUB_MANUAL\"}"), "ADMIN", 422);
        var cancelled = admin(post("/api/v1/class-sessions/" + id + "/cancellation").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"reason\":\"CLUB_MANUAL\",\"adminText\":\"Fictional D4c cancellation\"}"));
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED");
        var event = one("domain_events", Criteria.where("type").is("ClassCancelledByClub").and("aggregateId").is(id)).get("payload", Document.class);
        assertThat(event.getList("affected", Document.class)).hasSize(4); assertThat(event.getList("waitlistIds", String.class)).hasSize(2);
        var states = mongo.find(Query.query(Criteria.where("classSessionId").is(id)), Document.class, "bookings").stream()
                .collect(Collectors.groupingBy(b -> b.getString("state"), Collectors.counting()));
        assertThat(states).containsOnlyKeys("CANCELLED_BY_CLUB").containsEntry("CANCELLED_BY_CLUB", 4L);
        var entries = mongo.find(Query.query(Criteria.where("classSessionId").is(id)), Document.class, "waitlist_entries");
        assertThat(entries).hasSize(2).allSatisfy(e -> assertThat(e).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "CLASS_CANCELLED"));
        assertThat(mongo.findById(id, Document.class, "class_sessions").get("counters", Document.class)).containsEntry("booked", 0).containsEntry("waiting", 0);
    }

    @Test void T_06_28_seedSourcesContainNoRealStaffOrMockupFirstNames() throws Exception {
        // Mockup people of D3/D4/D4c/D7; the real staff file (DECISIONS_PENDENTS B28) is added to the check if it is ever committed.
        var names = new TreeSet<>(List.of("Estel", "Josep", "Neus", "Jordi", "Marc", "Núria"));
        for (var staff : List.of(Path.of("seeds/staff.canic.yaml"), Path.of("seed/clubs/canic/staff.canic.yaml"))) {
            if (Files.exists(staff)) {
                for (String line : Files.readAllLines(staff)) { var m = java.util.regex.Pattern.compile("name:\\s*\"?([^\"\\s]+)").matcher(line); if (m.find()) { names.add(m.group(1)); } }
            }
        }
        var sources = new ArrayList<>(List.of(Path.of("seeds/demo-canic.yaml"), Path.of("seeds/club-canic.yaml")));
        try (var files = Files.walk(Path.of("src/main/java"))) { files.filter(p -> p.getFileName().toString().startsWith("Demo")).forEach(sources::add); }
        assertThat(sources).hasSizeGreaterThan(8);
        for (var source : sources) {
            String text = Files.readString(source);
            for (String name : names) { assertThat(text).as(source + " " + name).doesNotContainPattern("\\b" + name + "\\b"); }
        }
    }
}
