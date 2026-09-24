package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5-T06: the demo scenario (seeds/demo-canic.yaml `scenario`, seeds/club-fifo.yaml + demo-fifo.yaml) holds every state
 * the E5 screens and the gate need at `demoNow` (Monday 14-09-2026 07:00 Madrid, the run being Wednesday 09-09 with
 * `--week-start=2026-09-14`), made through the real services, deterministic, idempotent and tenant-scoped (T-08-40 back half).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoScenarioSeedIT extends AbstractIntegrationTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate WEEK = LocalDate.parse("2026-09-14");
    static final Instant DEMO_NOW = WEEK.atTime(7, 0).atZone(MADRID).toInstant();
    static final List<String> SEEDED = List.of("weeks", "class_sessions", "ring_blocks", "bookings", "waitlist_entries", "training_bookings", "activities",
            "activity_registrations", "seat_locks", "domain_events");
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand command; @Autowired HostTenantResolver hosts;
    @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired MockMvc mvc; @Autowired JobRunner runner; @Autowired OutboxDispatcher dispatcher;
    @Autowired ClubConfigService configs;
    String club;

    @BeforeEach void clear() {
        for (String collection : mongo.getCollectionNames()) { if (!collection.startsWith("system.")) { mongo.remove(new Query(), collection); } }
        hosts.invalidate(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        club = definitions.apply(codec.read(Path.of("seeds/club-canic.yaml")), false).id();
    }
    void seed(String slug) throws Exception {
        command.run(new DefaultApplicationArguments("--club=" + slug, "--seed=42", "--week-start=" + WEEK));
    }
    String memberOf(String email) {
        var account = mongo.findOne(Query.query(Criteria.where("email").is(email)), Document.class, "accounts").getString("_id");
        return mongo.findOne(Query.query(Criteria.where("accountId").is(account)), Document.class, "members").getString("_id");
    }
    JsonNode member(String email, String clubId, String host, String path, int expected) throws Exception {
        var account = mongo.findOne(Query.query(Criteria.where("email").is(email)), Document.class, "accounts").getString("_id");
        String memberId = memberOf(email);
        var body = mvc.perform(get(path).header("Host", host).with(jwt().jwt(j -> j.subject(account).claim("clubId", clubId).claim("memberId", memberId))
                .authorities(() -> "ROLE_MEMBER"))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }
    JsonNode canic(String email, String path) throws Exception { return member(email, club, "app.agilitycanic.cat", path, 200); }
    String dog(String email, String level) {
        String levelId = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("code").is(level)), Document.class, "levels").getString("_id");
        return mongo.find(Query.query(Criteria.where("memberId").is(memberOf(email)).and("levelId").is(levelId)), Document.class, "dogs").stream()
                .map(d -> d.getString("_id")).sorted().findFirst().orElseThrow();
    }
    static List<String> states(JsonNode bookable) {
        return StreamSupport.stream(bookable.path("classes").spliterator(), false).map(c -> c.path("state").asText()).distinct().sorted().toList();
    }
    static List<JsonNode> week(JsonNode bookable, String week) {
        return StreamSupport.stream(bookable.path("classes").spliterator(), false).filter(c -> c.path("week").asText().equals(week)).toList();
    }
    Document session(LocalDate date, String start, String ring) {
        String ringId = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("shortName").is(ring)), Document.class, "rings").getString("_id");
        return mongo.findOne(Query.query(Criteria.where("date").is(date.toString()).and("startTime").is(start).and("ringId").is(ringId)), Document.class, "class_sessions");
    }

    @Test void T_08_40_scenarioHoldsEveryScreenStateAtDemoNowThroughTheRealServices() throws Exception {
        seed("canic");
        var run = mongo.findById(club + ":planning", Document.class, "demo_seed_runs").get("counts", Document.class);
        assertThat(run).containsEntry("scenarioClassBookings", 18).containsEntry("scenarioCancellations", 3).containsEntry("scenarioWaitlist", 5)
                .containsEntry("scenarioRiskExempt", 1).containsEntry("scenarioBookingBlocks", 1).containsEntry("scenarioTrainingBookings", 3)
                .containsEntry("scenarioTrainingOverrides", 1).containsEntry("scenarioRingBlocks", 1).containsEntry("scenarioActivityRegistrations", 1)
                .containsEntry("validatedWeeks", 2);
        clock.setInstant(DEMO_NOW);

        // 03: every row type for member@ (CLASS, CLASS_WAITLIST, TRAINING ×2, ACTIVITY), chronological, counters of W0.
        var home = canic("member@example.test", "/api/v1/me/home");
        var types = StreamSupport.stream(home.path("reservations").spliterator(), false).map(r -> r.path("type").asText()).toList();
        assertThat(types).containsSubsequence("CLASS", "TRAINING", "TRAINING", "CLASS_WAITLIST", "ACTIVITY");
        var starts = StreamSupport.stream(home.path("reservations").spliterator(), false).map(r -> Instant.parse(r.path("startsAt").asText())).toList();
        assertThat(starts).isSorted();
        assertThat(home.at("/limits/currentWeek/count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(home.at("/limits/currentWeek/weekKey").asText()).isEqualTo("2026-09-13");

        // 04: the six live row states across the scenario members (PACK_EMPTY needs S12 packs, FULL needs WAITLIST off).
        var all = new TreeSet<String>();
        var m5 = canic("member@example.test", "/api/v1/me/bookable-classes?dogId=" + dog("member@example.test", "E"));
        all.addAll(states(m5));
        assertThat(states(m5)).contains("BOOKABLE", "WAITLIST_FULL", "NOT_YET_OPEN");
        var m8 = canic("member.4@example.test", "/api/v1/me/bookable-classes?dogId=" + dog("member.4@example.test", "E"));
        assertThat(states(m8)).contains("WAITLIST_OPEN"); all.addAll(states(m8));
        var m7 = canic("member.3@example.test", "/api/v1/me/bookable-classes?dogId=" + dog("member.3@example.test", "E"));
        assertThat(week(m7, "CURRENT")).isNotEmpty().allSatisfy(c -> assertThat(c.path("state").asText()).isEqualTo("WEEKLY_LIMIT_DONE"));
        assertThat(week(m7, "LATER")).isNotEmpty().allSatisfy(c -> assertThat(c.path("state").asText()).isEqualTo("NOT_YET_OPEN"));
        all.addAll(states(m7));
        var m11 = canic("member.7@example.test", "/api/v1/me/bookable-classes");
        assertThat(m11.at("/bookingBlock/reason").asText()).isEqualTo("Quota pendent (fictícia)");
        assertThat(m11.path("classes")).isNotEmpty().allSatisfy(c -> assertThat(c.path("notBookableReason").asText()).isEqualTo("BLOCKED"));
        all.addAll(states(m11));
        assertThat(all).containsExactly("BOOKABLE", "NOT_BOOKABLE", "NOT_YET_OPEN", "WAITLIST_FULL", "WAITLIST_OPEN", "WEEKLY_LIMIT_DONE");

        // 06 / 29 / 07 data: member.2@ at the limit with one swappable and one done; member.3@ with one in-time and one late cancellation.
        var m6 = memberOf("member.2@example.test");
        var m6Bookings = mongo.find(Query.query(Criteria.where("memberId").is(m6).and("bookingWeekKey").is("2026-09-13")), Document.class, "bookings");
        assertThat(m6Bookings).extracting(b -> b.getString("state")).containsExactlyInAnyOrder("ACTIVE", "CANCELLED_LATE");
        var m7Bookings = mongo.find(Query.query(Criteria.where("memberId").is(memberOf("member.3@example.test"))), Document.class, "bookings");
        assertThat(m7Bookings).extracting(b -> b.getString("state") + ":" + b.getBoolean("late")).containsExactlyInAnyOrder("ACTIVE:null", "CANCELLED_LATE:true", "CANCELLED:false");
        assertThat(m7Bookings).extracting(b -> b.getString("dogId")).containsOnly(dog("member.3@example.test", "E"));

        // 08: member@'s E dog has 2/3 in the training week; the CAD dog trains by override; member.4@'s CAD dog has no right.
        var summary = canic("member@example.test", "/api/v1/me/training-summary?dogId=" + dog("member@example.test", "E"));
        assertThat(summary.at("/counter/used").asInt()).isEqualTo(2); assertThat(summary.at("/counter/limit").asInt()).isEqualTo(3);
        var sources = new HashMap<String, String>(); summary.path("eligibleDogs").forEach(d -> sources.put(d.path("id").asText(), d.path("rightSource").asText()));
        assertThat(sources).containsEntry(dog("member@example.test", "E"), "LEVEL").containsEntry(dog("member@example.test", "CAD"), "MANUAL");
        var noRight = canic("member.4@example.test", "/api/v1/me/training-summary");
        assertThat(noRight.path("eligibleDogs")).extracting(d -> d.path("id").asText()).doesNotContain(dog("member.4@example.test", "CAD"));
        var block = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("kind").is("RESERVATION")), Document.class, "ring_blocks");
        assertThat(block.getString("reason")).isEqualTo("PRIVATE_CLASS"); assertThat(block.getString("note")).isEqualTo("Classe particular (fictícia)");

        // S15 P2 fixture: Tuesday 18:50 with 1 registrant, Wednesday 16:30 with none, Tuesday 20:00 MUN exempt.
        assertThat(session(WEEK.plusDays(1), "18:50", "CEN").get("counters", Document.class)).containsEntry("booked", 1);
        assertThat(session(WEEK.plusDays(2), "16:30", "CEN").get("counters", Document.class)).containsEntry("booked", 0);
        assertThat(session(WEEK.plusDays(1), "20:00", "MUN").get("risk", Document.class)).containsEntry("exempt", true);
        var full = session(WEEK.plusDays(1), "17:40", "CAR");
        assertThat(full.get("counters", Document.class)).containsEntry("booked", full.getInteger("capacity")).containsEntry("waiting", 3);
        var open = session(WEEK.plusDays(3), "17:40", "CAR");
        assertThat(open.get("counters", Document.class)).containsEntry("booked", open.getInteger("capacity")).containsEntry("waiting", 2);
        assertThat(session(WEEK.plusDays(3), "20:00", "CEN").get("counters", Document.class)).containsEntry("booked", 2);

        // The counters the services keep match the documents; every seeded document belongs to the tenant.
        for (var c : mongo.find(Query.query(Criteria.where("clubId").is(club).and("state").is("ACTIVE")), Document.class, "class_sessions")) {
            long booked = mongo.count(Query.query(Criteria.where("classSessionId").is(c.getString("_id")).and("state").in("ACTIVE", "PAYMENT_PENDING")), "bookings");
            long waiting = mongo.count(Query.query(Criteria.where("classSessionId").is(c.getString("_id")).and("state").in("ACTIVE", "NOTIFIED")), "waitlist_entries");
            assertThat(c.get("counters", Document.class)).as(c.getString("date") + " " + c.getString("startTime"))
                    .containsEntry("booked", (int) booked).containsEntry("waiting", (int) waiting);
        }
        for (String collection : SEEDED) {
            assertThat(mongo.count(new Query(), collection)).as(collection).isPositive();
            // Global identity events carry no club; nothing seeded carries another club.
            assertThat(mongo.count(Query.query(Criteria.where("clubId").nin(club, null)), collection)).as(collection).isZero();
        }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(null)), "bookings")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("origin").ne("APP")), "bookings")).isZero();
    }

    @Test void T_08_40_scenarioIsDeterministicAndASecondRunChangesNothing() throws Exception {
        seed("canic"); var first = bookings(); var saved = snapshot();
        seed("canic"); assertThat(snapshot()).isEqualTo(saved);
        clear(); seed("canic"); assertThat(bookings()).isEqualTo(first);
    }

    @Test void T_08_40_fifoClubHoldsTheP6AndP7WorkAtDemoNow() throws Exception {
        String fifo = definitions.apply(codec.read(Path.of("seeds/club-fifo.yaml")), false).id();
        seed("fifo");
        var entries = mongo.find(Query.query(Criteria.where("clubId").is(fifo)).with(org.springframework.data.domain.Sort.by("position")), Document.class, "waitlist_entries");
        assertThat(entries).extracting(e -> e.getString("state")).containsExactly("NOTIFIED", "ACTIVE", "ACTIVE");
        assertThat(entries.getFirst().getDate("confirmBy").toInstant()).isEqualTo(WEEK.minusDays(1).atTime(21, 30).atZone(MADRID).toInstant());
        var pending = mongo.findOne(Query.query(Criteria.where("clubId").is(fifo).and("state").is("PAYMENT_PENDING")), Document.class, "bookings");
        assertThat(pending).isNotNull(); assertThat(pending.get("charge", Document.class).getString("mode")).isEqualTo("PAY_TO_BOOK");
        assertThat(configs.get(fifo).get("waitlist.mode", String.class)).isEqualTo("FIFO");
        clock.setInstant(DEMO_NOW);
        try (var tenant = TenantContext.open(fifo)) {
            runner.manual(fifo, JobName.WAITLIST_FIFO, false, null);
            runner.manual(fifo, JobName.PAYMENT_TIMEOUTS, false, null);
        }
        for (int i = 0; i < 6; i++) { dispatcher.dispatch(); }
        var after = mongo.find(Query.query(Criteria.where("clubId").is(fifo)).with(org.springframework.data.domain.Sort.by("position")), Document.class, "waitlist_entries");
        assertThat(after).extracting(e -> e.getString("state")).containsExactly("EXPIRED", "NOTIFIED", "ACTIVE");
        var timedOut = mongo.findById(pending.getString("_id"), Document.class, "bookings");
        assertThat(timedOut.getString("state")).isEqualTo("CANCELLED"); assertThat(timedOut.getString("cancelReason")).isEqualTo("PAYMENT_TIMEOUT");
        for (String collection : List.of("bookings", "waitlist_entries", "class_sessions", "members", "dogs")) {
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(fifo)), collection)).as(collection).isPositive();
        }
    }

    @Test void T_08_40_scenarioIsSkippedWhenItsWeekHasAlreadyStarted() throws Exception {
        command.run(new DefaultApplicationArguments("--club=canic", "--seed=42", "--week-start=2026-09-07"));
        var run = mongo.findById(club + ":planning", Document.class, "demo_seed_runs").get("counts", Document.class);
        assertThat(run).containsEntry("scenarioClassBookings", 0).containsEntry("scenarioTrainingBookings", 0).containsEntry("scenarioRingBlocks", 0);
        assertThat(mongo.count(new Query(), "training_bookings")).isZero();
    }

    Map<String, List<Document>> snapshot() {
        var result = new TreeMap<String, List<Document>>();
        for (String name : mongo.getCollectionNames()) { result.put(name, mongo.findAll(Document.class, name)); } return result;
    }
    /** Class slot → the member numbers and states of its bookings and entries, independent of generated ids. */
    Map<String, List<String>> bookings() {
        var result = new TreeMap<String, List<String>>(); var rings = new HashMap<String, String>();
        mongo.findAll(Document.class, "rings").forEach(r -> rings.put(r.getString("_id"), r.getString("shortName")));
        var numbers = new HashMap<String, String>(); mongo.findAll(Document.class, "members").forEach(m -> numbers.put(m.getString("_id"), String.valueOf(m.get("memberNumber"))));
        for (String collection : List.of("bookings", "waitlist_entries", "training_bookings")) {
            for (var b : mongo.findAll(Document.class, collection)) {
                String key = collection.equals("training_bookings") ? b.getDate("startsAt").toInstant() + " " + rings.get(b.getString("ringId"))
                        : Optional.ofNullable(mongo.findById(b.getString("classSessionId"), Document.class, "class_sessions"))
                                .map(c -> c.getString("date") + " " + c.getString("startTime") + " " + rings.get(c.getString("ringId"))).orElse("?");
                result.computeIfAbsent(key + " " + collection, k -> new ArrayList<>()).add(numbers.get(b.getString("memberId")) + ":" + b.getString("state"));
            }
        }
        result.values().forEach(Collections::sort); return result;
    }
}
