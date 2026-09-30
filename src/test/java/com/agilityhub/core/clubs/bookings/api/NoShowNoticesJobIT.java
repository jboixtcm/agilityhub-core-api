package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine;
import com.agilityhub.core.support.NotificationRows;
import com.agilityhub.core.clubs.bookings.application.jobs.NoShowNoticesJob;
import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.*;

/**
 * S15 R-15-13 P3 `no-show-notices` (E6-T04: T-15-16, T-10-26) on the fictional S08 club: the single batch at the club's
 * 08:00 — one `NoShowNoticeBatch` item per club and day (round 2, review #4) —, the plan that equals the effects,
 * idempotency, late marks, marks changed before, during and after the batch, and N-19 through the S11 engine (one per
 * booking, the class's own date and description, the `PERSONAL` e-mail preference, `noShowNotice.sentAt`). Also a club on
 * Buenos Aires time.
 */
class NoShowNoticesJobIT extends BookingFixtures {
    static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    @Autowired JobRunner runner; @Autowired NoShowNoticesJob job; @Autowired NotificationEngine engine; @Autowired org.springframework.transaction.PlatformTransactionManager notificationTransactions; @Autowired EmailSender email;

    @BeforeEach void jobs() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(new Query(), "job_locks");
    }
    JsonNode sheet(String classId, RequestPostProcessor auth) throws Exception { return call(HttpMethod.GET, "/class-sessions/s08-" + classId + "/attendance", null, auth, 200); }
    JsonNode save(String classId, RequestPostProcessor auth, long version, Object... items) throws Exception {
        var list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < items.length; i += 2) { list.add(Map.of("bookingId", items[i], "state", items[i + 1])); }
        return call(HttpMethod.PUT, "/class-sessions/s08-" + classId + "/attendance", Map.of("version", version, "items", list), auth, 200, UUID.randomUUID().toString());
    }
    String id(JsonNode booking) { return booking.path("id").asText(); }
    Document attendance(String bookingId) { return mongo.findOne(Query.query(Criteria.where("bookingId").is(bookingId)), Document.class, "attendances"); }
    String attendanceId(String bookingId) { return attendance(bookingId).getString("_id"); }
    Map<String, Object> counters(JobRun run) {
        var map = new TreeMap<String, Object>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
    /** The attendances an item of the batch traces (`detail.attendances`), in its order. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> batch(JobRun.Item item) {
        return (List<Map<String, Object>>) item.detail().stream().filter(e -> e.key().equals("attendances")).findFirst().orElseThrow().value();
    }
    static List<Object> batchIds(JobRun.Item item) { return batch(item).stream().map(row -> row.get("attendanceId")).toList(); }
    List<Document> n19Rows(String eventId) {
        return NotificationRows.rows(mongo, CLUB, Criteria.where("code").is("N-19").and("eventId").is(eventId));
    }
    long allEvents() { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "domain_events"); }
    static String fullDate(LocalDate date, String language) { return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.forLanguageTag(language))); }

    @Test void T_15_16_T_10_26_threeOfYesterdayAndALateMarkMakeOneBatchOfFourWithOneLateAndASecondRunEmitsNothing() throws Exception {
        session("tue", "2026-10-06T18:50", 3, List.of());
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-joan")), new Update().set("locale", "es"), "accounts");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-pere")), new Update().set("locale", "en"), "accounts");
        String tue = id(book(as("c2"), "tue", "s08-d-c2")), wed = id(book(as("c3"), "wed", "s08-d-c3"));
        String duna = id(book(as("laura"), "thu", "s08-d-duna")), toby = id(book(as("joan"), "thu", "s08-d-toby")), nit = id(book(as("pere"), "thu", "s08-d-nit"));
        String changed = id(book(as("c1"), "last", "s08-d-c1"));
        // Wednesday evening Crowd3 did not come; Thursday's 08:00 batch notifies it (the «already notified» one of T-15-16).
        clock.setInstant(local("2026-10-07T20:00"));
        save("wed", as("inst"), 0, wed, "NO_SHOW");
        clock.setInstant(local("2026-10-08T08:00"));
        var thursday = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(thursday.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(thursday.items()).singleElement().satisfies(item -> {
            assertThat(item.entityType()).isEqualTo("NoShowNoticeBatch"); assertThat(item.entityId()).isEqualTo("2026-10-08");
            assertThat(batchIds(item)).containsExactly(attendanceId(wed));
        });
        assertThat(counters(thursday)).containsEntry("notices", 1L).containsEntry("late", 0L);
        // Thursday 20:00: three no-shows on the 18:50 class and one on the 20:00; the admin adds Tuesday's two days late (R-10-03 override).
        clock.setInstant(local("2026-10-08T20:00"));
        save("thu", as("inst"), 0, duna, "NO_SHOW", toby, "NO_SHOW", nit, "NO_SHOW");
        save("last", as("inst"), 0, changed, "NO_SHOW");
        save("tue", as("admin"), 0, tue, "NO_SHOW");
        // 22:00: the 20:00 one is corrected before the batch, so it is never notified.
        clock.setInstant(local("2026-10-08T22:00"));
        save("last", as("inst"), 1, changed, "PRESENT");

        // Friday 07:59: nothing is due (Thursday's occurrence ran); the club's 08:00 is.
        clock.setInstant(local("2026-10-09T07:59"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant())).isEmpty();
        clock.setInstant(local("2026-10-09T08:00"));
        long eventsBefore = allEvents();
        var dry = runner.manual(CLUB, JobName.NO_SHOW_NOTICES, true, "s08-admin");
        assertThat(dry.dryRun()).isTrue();
        // One item for the club's day, the attendances in its detail (R-15-13 `WOULD_NOTIFY {attendanceId, memberId, dogName, classDate}`).
        assertThat(dry.items()).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("WOULD_NOTIFY"); assertThat(item.entityType()).isEqualTo("NoShowNoticeBatch");
            assertThat(item.entityId()).isEqualTo("2026-10-09"); assertThat(item.detail()).contains(new JobRun.Entry("date", "2026-10-09"));
        });
        var planned = batch(dry.items().getFirst());
        assertThat(planned).extracting(row -> row.get("attendanceId")).containsExactlyInAnyOrder(attendanceId(tue), attendanceId(duna), attendanceId(toby), attendanceId(nit));
        // The claim's order: class date, then id — Tuesday's late mark first.
        assertThat(planned.getFirst()).containsOnlyKeys("attendanceId", "bookingId", "memberId", "dogName", "classDate").containsEntry("bookingId", tue)
                .containsEntry("memberId", "s08-m-c2").containsEntry("dogName", "Dog2").containsEntry("classDate", "2026-10-06");
        assertThat(counters(dry)).isEqualTo(Map.of("WOULD_NOTIFY", 1L));
        // R-15-08: the dry run wrote nothing but its JobRun.
        assertThat(allEvents()).isEqualTo(eventsBefore);
        assertThat(attendance(duna).get("noShowNotice")).isNull();

        var run = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(run.trigger()).isEqualTo(JobTrigger.SCHEDULE); assertThat(run.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(run.scheduledFor()).isEqualTo(Instant.parse("2026-10-09T06:00:00Z")); assertThat(run.scheduledForLocal()).isEqualTo("2026-10-09T08:00");
        // The plan equals the effects (same item, same detail: the claim took exactly the planned attendances), and the batch
        // counters: 4 notices, 1 late (Tuesday's).
        assertThat(run.items()).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("NOTIFY"); assertThat(item.entityId()).isEqualTo("2026-10-09");
            assertThat(item.detail()).containsExactlyInAnyOrderElementsOf(dry.items().getFirst().detail());
        });
        assertThat(batch(run.items().getFirst())).isEqualTo(planned);
        assertThat(counters(run)).isEqualTo(Map.of("notices", 4L, "late", 1L));
        assertThat(run.parametersSnapshot()).contains(new JobRun.Entry("messaging.noShowNoticeTime", "08:00"), new JobRun.Entry("jobs.noShowNotices.enabled", true));
        var batches = eventsOf("NoShowNoticeDue");
        assertThat(batches).hasSize(2);
        var friday = batches.stream().filter(e -> e.get("payload", Document.class).getList("bookingIds", String.class).size() == 4).findFirst().orElseThrow();
        assertThat(friday.get("payload", Document.class).getList("bookingIds", String.class)).containsExactlyInAnyOrder(tue, duna, toby, nit);
        assertThat(friday.get("payload", Document.class).getList("attendanceIds", String.class)).containsExactlyElementsOf(
                planned.stream().map(row -> (String) row.get("attendanceId")).toList());
        String eventId = friday.getString("_id");
        for (String booking : List.of(tue, duna, toby, nit)) {
            assertThat(attendance(booking).get("noShowNotice", Document.class)).containsEntry("eventId", eventId);
            assertThat(attendance(booking).get("noShowNotice", Document.class).getDate("queuedAt").toInstant()).isEqualTo(clock.instant());
        }
        assertThat(attendance(changed).get("noShowNotice")).isNull();
        System.out.println("E6-T04 no-show-notices JobRun dry  " + mongo.findById(dry.id(), Document.class, "job_runs").toJson());
        System.out.println("E6-T04 no-show-notices JobRun real " + mongo.findById(run.id(), Document.class, "job_runs").toJson());

        // R-15-04: [Executa ara] right after the scheduled run claims nothing and emits nothing.
        var again = runner.manual(CLUB, JobName.NO_SHOW_NOTICES, false, "s08-admin");
        assertThat(again.items()).isEmpty(); assertThat(again.counters()).isEmpty();
        assertThat(events("NoShowNoticeDue")).isEqualTo(2);

        // N-19: one per booking to the dog's owner, APP + EMAIL, in the owner's language, with the class's own date.
        dispatch();
        var rows = n19Rows(eventId);
        assertThat(rows).extracting(n -> n.getString("channel") + " " + n.getString("accountId")).containsExactlyInAnyOrder(
                "APP s08-c2", "EMAIL s08-c2", "APP s08-laura", "EMAIL s08-laura", "APP s08-joan", "EMAIL s08-joan", "APP s08-pere", "EMAIL s08-pere");
        assertThat(rows).allSatisfy(n -> assertThat(n.getString("status")).isEqualTo("APP".equals(n.getString("channel")) ? "DELIVERED" : "SENT"));
        var laura = rows.stream().filter(n -> "APP".equals(n.getString("channel")) && duna.equals(n.get("subject", Document.class).getString("bookingId"))).findFirst().orElseThrow().get("variables", Document.class);
        assertThat(laura.getString("dog_name")).isEqualTo("Duna"); assertThat(laura.getString("entityId")).isEqualTo(duna);
        assertThat(laura.getString("class_date")).isEqualTo(fullDate(LocalDate.of(2026, 10, 8), "ca"));
        assertThat(laura.getString("class_description")).as("E65: the class's description").isEqualTo("Classe thu");
        var crowd = rows.stream().filter(n -> "APP".equals(n.getString("channel")) && tue.equals(n.get("subject", Document.class).getString("bookingId"))).findFirst().orElseThrow().get("variables", Document.class);
        assertThat(crowd.getString("class_date")).as("a late mark keeps its own class date").isEqualTo(fullDate(LocalDate.of(2026, 10, 6), "ca"));
        assertThat(crowd.getString("class_description")).isEqualTo("Classe tue");
        var mail = (FakeEmailSender) email;
        assertThat(mail.lastTo("s08-laura@example.test").subject()).isEqualTo("T'hem trobat a faltar");
        // E7-T03: S11 §8's N-19 text, «[[class_date]] no vas poder venir a la classe de [[class_description]]», the date in full.
        var capital = (java.util.function.UnaryOperator<String>) com.agilityhub.core.clubs.messaging.application.engine.TemplateRenderer::capitalize;
        assertThat(mail.lastTo("s08-laura@example.test").text()).contains(capital.apply(fullDate(LocalDate.of(2026, 10, 8), "ca")) + " no vas poder venir a la classe de Classe thu")
                .doesNotContain("ahir");
        assertThat(mail.lastTo("s08-joan@example.test").subject()).isEqualTo("Te hemos echado de menos");
        assertThat(mail.lastTo("s08-joan@example.test").text()).contains(capital.apply(fullDate(LocalDate.of(2026, 10, 8), "es")) + " no pudiste venir a la clase de Classe thu")
                .doesNotContain("ayer");
        assertThat(mail.lastTo("s08-pere@example.test").subject()).isEqualTo("We missed you");
        assertThat(mail.lastTo("s08-pere@example.test").text()).contains("On " + fullDate(LocalDate.of(2026, 10, 8), "en") + " you couldn't make it to the Classe thu class")
                .doesNotContain("yesterday");
        System.out.println("E6-T04 N-19 rows " + rows.stream().map(n -> n.getString("channel") + " " + n.getString("accountId") + " " + n.getString("locale")).sorted().toList());
        // S10 §7 «avís ja enviat»: the four rows carry sentAt, and the sheet shows it.
        for (String booking : List.of(tue, duna, toby, nit)) { assertThat(attendance(booking).get("noShowNotice", Document.class).get("sentAt")).isNotNull(); }
        assertThat(sheet("thu", as("inst")).path("rows").findValues("noShowNotice")).allSatisfy(notice -> assertThat(notice.path("sentAt").isNull()).isFalse());
        Instant sentAt = attendance(duna).get("noShowNotice", Document.class).getDate("sentAt").toInstant();
        // A redelivered batch notifies nobody twice and keeps the first sentAt.
        clock.setInstant(local("2026-10-09T09:00"));
        NotificationRows.deliver(engine, notificationTransactions, eventId, new AttendanceEvent(AttendanceEvent.Kind.NoShowNoticeDue, CLUB, CLUB, clock.instant(), friday.get("payload", Document.class), null, null,
                DomainEvent.Origin.SYSTEM));
        assertThat(n19Rows(eventId)).hasSize(8);
        assertThat(attendance(duna).get("noShowNotice", Document.class).getDate("sentAt").toInstant()).isEqualTo(sentAt);
        // A mark changed after the batch is still corrected; the notice already out is not withdrawn (R-10-06).
        save("thu", as("inst"), 1, duna, "PRESENT");
        assertThat(attendance(duna).getString("state")).isEqualTo("PRESENT");
        assertThat(attendance(duna).get("noShowNotice", Document.class)).containsEntry("eventId", eventId);
        dispatch();
        assertThat(NotificationRows.rows(mongo, CLUB, "N-19")).hasSize(10);
    }

    /**
     * E6-T04 round 2 (review #4, ruling E65): P3 is one item per club and day, applied as one claim. B is planned, is changed
     * to PRESENT before the claim and back to NO_SHOW before the run ends (an instructor correcting the sheet twice while the
     * batch runs): exactly one `NoShowNoticeDue`, for A only. B is not in that batch; it stays a candidate for the next one.
     */
    @Test void T_15_16_R_15_13_aMarkThatLeavesTheScopeAndComesBackDuringTheRunNeverMakesASecondBatch() throws Exception {
        String a = id(book(as("laura"), "thu", "s08-d-duna")), b = id(book(as("joan"), "thu", "s08-d-toby"));
        clock.setInstant(local("2026-10-08T20:00"));
        save("thu", as("inst"), 0, a, "NO_SHOW", b, "NO_SHOW");
        String bId = attendanceId(b);
        var marks = new ArrayList<String>();
        Job interleaved = new Job() {
            @Override public JobName name() { return JobName.NO_SHOW_NOTICES; }
            @Override public List<JobItem> plan(JobContext context) {
                var plan = job.plan(context);
                assertThat(plan.toString()).as("B is planned").contains(bId);
                mark(bId, "PRESENT"); // corrected before the claim …
                return plan;
            }
            @Override public JobEffect apply(JobContext context, JobItem item) {
                var effect = job.apply(context, item);
                mark(bId, "NO_SHOW"); // … and back to NO_SHOW before the run ends
                return effect;
            }
            private void mark(String attendanceId, String state) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(attendanceId)), new Update().set("state", state).inc("version", 1), "attendances");
                marks.add(state);
            }
        };
        clock.setInstant(local("2026-10-09T08:00"));
        var run = runner.scheduled(CLUB, true, interleaved, clock.instant()).orElseThrow();
        assertThat(run.status()).isEqualTo(JobStatus.SUCCEEDED);
        var batches = eventsOf("NoShowNoticeDue");
        assertThat(batches).as("exactly one batch").singleElement()
                .satisfies(e -> assertThat(e.get("payload", Document.class).getList("bookingIds", String.class)).containsExactly(a));
        assertThat(marks).as("the edits happened during the run, around its one claim").containsExactly("PRESENT", "NO_SHOW");
        assertThat(counters(run)).isEqualTo(Map.of("notices", 1L, "late", 0L));
        assertThat(run.items()).singleElement().satisfies(item -> assertThat(batchIds(item)).containsExactly(attendanceId(a)));
        assertThat(attendance(b).getString("state")).isEqualTo("NO_SHOW");
        assertThat(attendance(b).get("noShowNotice")).as("B was not claimed by this run").isNull();
    }

    /**
     * E6-T04 round 2 (review #3, ruling E65): N-19's e-mail follows the owner's `PERSONAL` preference through the S11
     * engine's `ChannelResolver` — `emailByCategory.PERSONAL = false` → `SKIPPED_BY_PREFERENCE`, the APP notice still
     * delivered; explicitly on, or no stored preference (the product default, PERSONAL on) → sent. Question 2: N-19 carries
     * `class_description`, the class's description in the recipient's language.
     */
    @Test void T_15_16_T_10_26_n19EmailFollowsThePersonalPreferenceAndCarriesTheClassDescriptionInTheRecipientsLanguage() throws Exception {
        // A class described by its levels (no manual description): «C i sup.» / «C y sup.» / «C and up» (R-06-03).
        session("cd", "2026-10-08T15:00", 3, List.of("s08-lv-C", "s08-lv-D"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-cd")), new Update().unset("description"), "class_sessions");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-joan")), new Update().set("locale", "es"), "accounts");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-pere")), new Update().set("locale", "en"), "accounts");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("notificationPreferences", Map.of("emailByCategory", Map.of("PERSONAL", false))), "members");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-joan")), new Update().set("notificationPreferences", Map.of("emailByCategory", Map.of("PERSONAL", true))), "members");
        assertThat(mongo.findById("s08-m-pere", Document.class, "members").get("notificationPreferences")).as("Pere keeps the product default").isNull();
        String duna = id(book(as("laura"), "cd", "s08-d-duna")), toby = id(book(as("joan"), "cd", "s08-d-toby")), nit = id(book(as("pere"), "cd", "s08-d-nit"));
        clock.setInstant(local("2026-10-08T20:00"));
        save("cd", as("inst"), 0, duna, "NO_SHOW", toby, "NO_SHOW", nit, "NO_SHOW");
        var mail = (FakeEmailSender) email;
        long lauraMails = mail.messages().stream().filter(m -> m.to().equalsIgnoreCase("s08-laura@example.test")).count();
        clock.setInstant(local("2026-10-09T08:00"));
        var run = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(counters(run)).isEqualTo(Map.of("notices", 3L, "late", 0L));
        dispatch();
        String eventId = eventsOf("NoShowNoticeDue").getFirst().getString("_id");
        var rows = n19Rows(eventId);
        assertThat(rows).extracting(n -> n.getString("channel") + " " + n.getString("accountId") + " " + n.getString("status")).containsExactlyInAnyOrder(
                "APP s08-laura DELIVERED", "EMAIL s08-laura SKIPPED_BY_PREFERENCE",
                "APP s08-joan DELIVERED", "EMAIL s08-joan SENT",
                "APP s08-pere DELIVERED", "EMAIL s08-pere SENT");
        assertThat(mail.messages().stream().filter(m -> m.to().equalsIgnoreCase("s08-laura@example.test")).count()).as("no e-mail to the member who opted out").isEqualTo(lauraMails);
        assertThat(mail.lastTo("s08-joan@example.test").subject()).isEqualTo("Te hemos echado de menos");
        assertThat(mail.lastTo("s08-pere@example.test").subject()).isEqualTo("We missed you");
        // The opted-out member still has her notice in the app, so the sheet says «avís ja enviat».
        assertThat(attendance(duna).get("noShowNotice", Document.class).get("sentAt")).isNotNull();
        var descriptions = new TreeMap<String, String>();
        rows.stream().filter(n -> "APP".equals(n.getString("channel"))).forEach(n -> descriptions.put(n.getString("locale"), n.get("variables", Document.class).getString("class_description")));
        assertThat(descriptions).isEqualTo(Map.of("ca", "C i sup.", "es", "C y sup.", "en", "C and up"));
        System.out.println("E6-T04 N-19 preference rows " + rows.stream().map(n -> n.getString("channel") + " " + n.getString("accountId") + " " + n.getString("status")
                + " " + n.getString("locale") + " «" + n.get("variables", Document.class).getString("class_description") + "»").sorted().toList());
    }

    /**
     * T-11-33 (S11 contract with S15): `NoShowNoticeDue` with 3 bookings → 3 N-19, one per booking, through the engine. S11's
     * «`class_date = ahir`» is superseded by the owner of N-19's variables (S10 §8: «la classe de {class_date}», not «ahir»;
     * decision E65): the class was yesterday, and the notice names that day in full.
     */
    @Test void T_11_33_aBatchOfThreeNoShowsIsThreeN19WithYesterdaysClassDate() throws Exception {
        String duna = id(book(as("laura"), "thu", "s08-d-duna")), toby = id(book(as("joan"), "thu", "s08-d-toby")), nit = id(book(as("pere"), "thu", "s08-d-nit"));
        clock.setInstant(local("2026-10-08T20:00"));
        save("thu", as("inst"), 0, duna, "NO_SHOW", toby, "NO_SHOW", nit, "NO_SHOW");
        clock.setInstant(local("2026-10-09T08:00"));
        var run = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(counters(run)).isEqualTo(Map.of("notices", 3L, "late", 0L));
        var batch = eventsOf("NoShowNoticeDue").stream().filter(e -> e.get("payload", Document.class).getList("bookingIds", String.class).size() == 3).findFirst().orElseThrow();
        dispatch();
        var notices = NotificationRows.notifications(mongo, CLUB, "N-19").stream().filter(n -> batch.getString("_id").equals(n.getString("eventId"))).toList();
        assertThat(notices).hasSize(3);
        assertThat(notices).extracting(n -> n.get("subject", Document.class).getString("bookingId")).containsExactlyInAnyOrder(duna, toby, nit);
        assertThat(notices).extracting(n -> n.get("recipient", Document.class).getString("accountId")).containsExactlyInAnyOrder("s08-laura", "s08-joan", "s08-pere");
        String yesterday = fullDate(LocalDate.of(2026, 10, 8), "ca");
        assertThat(notices).allSatisfy(n -> {
            assertThat(n.getString("title")).isEqualTo("T'hem trobat a faltar");
            assertThat(n.get("variables", Document.class).getString("class_date")).isEqualTo(yesterday);
            // E7-T03: S11 §8's text opens with the class's own date in full, never «ahir».
            assertThat(n.getString("body")).startsWith(com.agilityhub.core.clubs.messaging.application.engine.TemplateRenderer.capitalize(yesterday) + " no vas poder venir")
                    .doesNotContain("ahir");
        });
        // A second delivery of the batch (the outbox's retry) adds nothing.
        NotificationRows.deliver(engine, notificationTransactions, batch.getString("_id"), new AttendanceEvent(AttendanceEvent.Kind.NoShowNoticeDue, CLUB, CLUB, clock.instant(),
                batch.get("payload", Document.class), null, null, DomainEvent.Origin.SYSTEM));
        assertThat(NotificationRows.notifications(mongo, CLUB, "N-19").stream().filter(n -> batch.getString("_id").equals(n.getString("eventId")))).hasSize(3);
    }

    /** A class on the club's own calendar (not the fixture's Madrid one). */
    void sessionIn(ZoneId zone, String id, String start) {
        var begins = LocalDateTime.parse(start); var starts = begins.atZone(zone).toInstant(); var s = new LinkedHashMap<String, Object>();
        s.put("id", "s08-" + id); s.put("clubId", CLUB); s.put("weekId", "s08-week"); s.put("date", begins.toLocalDate().toString()); s.put("startTime", start.substring(11));
        s.put("endTime", begins.plusHours(1).toLocalTime().toString()); s.put("startsAt", starts.toString()); s.put("endsAt", starts.plusSeconds(3600).toString());
        s.put("ringId", "s08-ring"); s.put("state", "ACTIVE"); s.put("levelIds", List.of()); s.put("instructorIds", List.of("s08-instructor")); s.put("capacity", 3);
        s.put("capacityMode", "MANUAL"); s.put("description", "Classe " + id); s.put("counters", Map.of("booked", 0, "waiting", 0));
        s.put("risk", Map.of("exempt", false, "notifiedBookingIds", List.of())); s.put("version", 0);
        mongo.insert(mapper.convertValue(s, com.agilityhub.core.clubs.scheduling.persistence.ClassSession.class));
    }

    @Test void T_10_26_T_15_16_aBuenosAiresClubGetsItsBatchAtItsOwnEightOClockWithItsOwnClassDate() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("timeZone", BUENOS_AIRES.getId()), "clubs");
        configs.invalidate(CLUB);
        // Thursday 08-10 22:00 in Buenos Aires is already Friday 01:00Z: the class date is the club's Thursday.
        sessionIn(BUENOS_AIRES, "ba", "2026-10-08T22:00");
        String duna = id(book(as("laura"), "ba", "s08-d-duna"));
        // Thursday 08:00 local (11:00Z) runs its occurrence with nothing to notify yet.
        clock.setInstant(Instant.parse("2026-10-08T11:00:00Z"));
        var thursday = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(thursday.scheduledForLocal()).isEqualTo("2026-10-08T08:00"); assertThat(thursday.timeZone()).isEqualTo(BUENOS_AIRES.getId());
        assertThat(thursday.items()).isEmpty();
        clock.setInstant(Instant.parse("2026-10-09T02:00:00Z")); // 23:00 local on Thursday
        save("ba", as("inst"), 0, duna, "NO_SHOW");
        assertThat(attendance(duna).getString("classDate")).isEqualTo("2026-10-08");
        // Madrid's 08:00 (06:00Z) and 10:59Z are nothing for this club; its own 08:00 (11:00Z) is the batch.
        for (String instant : List.of("2026-10-09T06:00:00Z", "2026-10-09T10:59:00Z")) {
            clock.setInstant(Instant.parse(instant));
            assertThat(runner.scheduled(CLUB, true, job, clock.instant())).as(instant).isEmpty();
        }
        clock.setInstant(Instant.parse("2026-10-09T11:00:00Z"));
        var run = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(run.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(run.scheduledFor()).isEqualTo(Instant.parse("2026-10-09T11:00:00Z")); assertThat(run.scheduledForLocal()).isEqualTo("2026-10-09T08:00");
        assertThat(run.items()).singleElement().satisfies(item -> {
            assertThat(item.entityId()).as("the club's own run date").isEqualTo("2026-10-09");
            assertThat(batch(item)).singleElement().satisfies(row -> assertThat(row).containsEntry("classDate", "2026-10-08"));
        });
        assertThat(counters(run)).isEqualTo(Map.of("notices", 1L, "late", 0L));
        dispatch();
        var rows = NotificationRows.rows(mongo, CLUB, "N-19").stream().filter(n -> "APP".equals(n.getString("channel"))).toList();
        assertThat(rows).singleElement().satisfies(n -> assertThat(n.get("variables", Document.class).getString("class_date")).isEqualTo(fullDate(LocalDate.of(2026, 10, 8), "ca")));
        System.out.println("E6-T04 no-show-notices Buenos Aires JobRun " + mongo.findById(run.id(), Document.class, "job_runs").toJson());
    }
}
