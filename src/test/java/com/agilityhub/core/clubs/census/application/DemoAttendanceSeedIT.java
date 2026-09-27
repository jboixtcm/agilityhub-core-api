package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.platform.application.definition.*;
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
 * E6-T04 step 5 (S10 WP-10-G): the demo seed's E6 data at `demoNow` (Monday 14-09-2026 07:00 Madrid; the run is Wednesday
 * 09-09 with `--week-start=2026-09-14`), made through the real services: the Monday 08:30 class of R-10-02's example (4
 * booked + one waiting), the holder's dog's 30-day metrics of T-10-04 (86 %, 7 classes, 2.3 trainings a week), screen 25's
 * badges, the two tasks (one with its attachment, one done by the owner), the member note on D14 and the private observation.
 * The cast has invented names (Rita + Mel, Martí + Sorra, Alba + Pinya, Nil + Trufa, Iu + Gira; instructor Berta), never the
 * mockups' first names (T-06-28).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoAttendanceSeedIT extends AbstractIntegrationTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate WEEK = LocalDate.parse("2026-09-14");
    static final Instant DEMO_NOW = WEEK.atTime(7, 0).atZone(MADRID).toInstant();
    static final String HOST = "app.agilitycanic.cat";
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand command; @Autowired HostTenantResolver hosts;
    @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired MockMvc mvc; @Autowired OutboxDispatcher dispatcher;
    String club;

    @BeforeEach void clear() {
        wipeDatabaseKeepingBootstrap();
        hosts.invalidate(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        club = definitions.apply(codec.read(Path.of("seeds/club-canic.yaml")), false).id();
        command.run(new DefaultApplicationArguments("--club=canic", "--seed=42", "--week-start=" + WEEK));
    }
    Document member(String firstName) { return mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("firstName").is(firstName)), Document.class, "members"); }
    Document dog(String name) { return mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("name").is(name)), Document.class, "dogs"); }
    JsonNode get(String path, Document member, String role) throws Exception {
        String account = member.getString("accountId"), memberId = member.getString("_id");
        var body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Host", HOST)
                .with(jwt().jwt(j -> j.subject(account).claim("clubId", club).claim("memberId", memberId)).authorities(() -> "ROLE_" + role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }
    static List<String> values(JsonNode array, String field) { return StreamSupport.stream(array.spliterator(), false).map(n -> n.path(field).asText()).toList(); }

    @Test void T_10_04_T_10_19_theE6DemoHoldsTheMondayClassTheHoldersMetricsAndEveryHistoryBadge() throws Exception {
        var counts = mongo.findById(club + ":planning", Document.class, "demo_seed_runs").get("counts", Document.class);
        assertThat(counts).containsEntry("attendanceHistoryClasses", 10).containsEntry("attendanceRenamed", 6).containsEntry("attendanceLevelChanges", 1)
                .containsEntry("attendanceBookings", 34).containsEntry("attendanceWaitlist", 1).containsEntry("attendanceMarks", 26)
                .containsEntry("attendanceCancellations", 2).containsEntry("attendanceNoShowBatches", 1).containsEntry("followupTasks", 2)
                .containsEntry("followupTaskAttachments", 1).containsEntry("followupObservations", 1).containsEntry("followupNotes", 1)
                .containsEntry("trainingHistoryOverrides", 1).containsEntry("trainingHistoryBookings", 10);
        System.out.println("E6-T04 demo seed counts " + counts.toJson());
        // The seed's outbox (every S08/S10/S03 event of the demo) is delivered as the running API would, 100 records a pass.
        for (int i = 0; i < 60; i++) { dispatcher.dispatch(); }
        clock.setInstant(DEMO_NOW);
        var holder = member("Rita"); var instructor = member("Berta"); var dog = dog("Mel");
        assertThat(holder).isNotNull(); assertThat(instructor).isNotNull(); assertThat(dog.getString("memberId")).isEqualTo(holder.getString("_id"));
        assertThat(dog.getString("handlerName")).isEqualTo("Rita");
        assertThat(mongo.findOne(Query.query(Criteria.where("_id").is(holder.getString("accountId"))), Document.class, "accounts").getString("email")).isEqualTo("member.8@example.test");
        assertThat(mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("shortName").is("Berta")), Document.class, "instructors")).isNotNull();

        // 21/D12: Monday 08:30 on Central, 4 of 5 booked (R-10-02's example) and one waiting.
        String ring = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("shortName").is("CEN")), Document.class, "rings").getString("_id");
        var monday = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("date").is(WEEK.toString()).and("startTime").is("08:30").and("ringId").is(ring)),
                Document.class, "class_sessions");
        var sheet = get("/api/v1/class-sessions/" + monday.getString("_id") + "/attendance", instructor, "INSTRUCTOR");
        assertThat(values(sheet.path("rows"), "dogName")).containsExactly("Mel", "Sorra", "Pinya", "Trufa");
        assertThat(values(sheet.path("rows"), "memberFirstName")).containsExactly("Rita", "Martí", "Alba", "Nil");
        assertThat(values(sheet.path("rows"), "state")).containsOnly("PENDING");
        assertThat(sheet.at("/classSession/capacity").asInt()).isEqualTo(5); assertThat(sheet.at("/classSession/booked").asInt()).isEqualTo(4);
        assertThat(values(sheet.at("/waitlist/entries"), "dogName")).containsExactly("Gira");
        assertThat(values(sheet.at("/waitlist/entries"), "memberFirstName")).containsExactly("Iu");

        // 22/D13: R-10-08 on Mel's last 30 days — present 6, no-show 1, notified in time 1 → 86 %, 7 classes; 10 trainings → 2.3.
        var card = get("/api/v1/dogs/" + dog.getString("_id") + "/instructor-card", instructor, "INSTRUCTOR");
        assertThat(card.at("/metrics/attendancePct").asInt()).isEqualTo(86); assertThat(card.at("/metrics/classesCounted").asInt()).isEqualTo(7);
        assertThat(card.at("/metrics/present").asInt()).isEqualTo(6); assertThat(card.at("/metrics/noShow").asInt()).isEqualTo(1);
        assertThat(card.at("/metrics/notified").asInt()).isEqualTo(1); assertThat(card.at("/metrics/cancelledLate").asInt()).isZero();
        assertThat(card.at("/metrics/trainingsCount").asInt()).isEqualTo(10); assertThat(card.at("/metrics/trainingsPerWeek").asDouble()).isEqualTo(2.3);
        assertThat(card.path("lastClasses")).hasSize(5);
        assertThat(card.at("/tasks/pendingCount").asInt()).isEqualTo(1); assertThat(card.at("/tasks/doneCount").asInt()).isEqualTo(1);
        assertThat(card.at("/instructorNote/text").asText()).contains("túnel");
        assertThat(card.at("/observations/text").asText()).contains("progressió");
        System.out.println("E6-T04 Mel card metrics at demoNow " + card.path("metrics"));

        // 25: the holder's history holds every class badge of R-10-14 and the trainings.
        var history = get("/api/v1/me/history?dogId=" + dog.getString("_id"), holder, "MEMBER");
        var classes = StreamSupport.stream(history.path("items").spliterator(), false).filter(i -> i.path("type").asText().equals("CLASS")).toList();
        assertThat(classes.stream().map(i -> i.path("state").asText()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("DONE", "NO_SHOW", "CANCELLED", "CANCELLED_LATE", "CANCELLED_BY_CLUB");
        assertThat(classes).filteredOn(i -> i.path("state").asText().equals("CANCELLED")).singleElement()
                .satisfies(i -> assertThat(i.at("/detail/kind").asText()).isEqualTo("INSTRUCTOR_NOTICE_IN_TIME"));
        assertThat(classes).filteredOn(i -> i.path("state").asText().equals("CANCELLED_BY_CLUB")).singleElement()
                .satisfies(i -> { assertThat(i.at("/detail/kind").asText()).isEqualTo("BY_CLUB"); assertThat(i.at("/detail/message").asText()).contains("manteniment"); });
        assertThat(StreamSupport.stream(history.path("items").spliterator(), false).filter(i -> i.path("type").asText().equals("TRAINING"))).hasSize(10);

        // Tasks: the pending one carries its uploaded video; the done one was completed by the owner. D14: her note, unread for the instructor.
        var tasks = mongo.find(Query.query(Criteria.where("clubId").is(club).and("dogId").is(dog.getString("_id"))), Document.class, "tasks");
        assertThat(tasks).extracting(t -> t.getString("state")).containsExactlyInAnyOrder("PENDING", "DONE");
        assertThat(tasks).filteredOn(t -> t.getString("state").equals("DONE")).singleElement()
                .satisfies(t -> assertThat(t.get("doneBy", Document.class).getString("displayName")).isEqualTo("Rita"));
        assertThat(tasks).extracting(t -> t.get("createdBy", Document.class).getString("displayName")).containsOnly("Berta");
        var attachments = mongo.find(Query.query(Criteria.where("clubId").is(club).and("entityType").is("TASK")), Document.class, "attachments");
        assertThat(attachments).singleElement().satisfies(a -> { assertThat(a.getString("mimeType")).isEqualTo("video/mp4"); assertThat(a.get("sizeBytes", Number.class).longValue()).isEqualTo(4096L); });
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club).and("kind").is("MEMBER_NOTE").and("dogId").is(dog.getString("_id"))), "followup_items")).isEqualTo(1);
        assertThat(get("/api/v1/followup/unread-count", instructor, "INSTRUCTOR").path("count").asInt()).isGreaterThanOrEqualTo(1);
        // The P3 batch of the morning after the history no-show was claimed by the seed (its N-19 went out through the outbox).
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club).and("state").is("NO_SHOW").and("noShowNotice.queuedAt").ne(null)), "attendances")).isEqualTo(1);
        assertThat(com.agilityhub.core.support.NotificationRows.count(mongo,Criteria.where("clubId").is(club).and("code").is("N-19").and("accountId").is(holder.getString("accountId")))).isEqualTo(2);
    }
}
