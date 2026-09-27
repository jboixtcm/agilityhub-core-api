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
 * 08:00, the plan that equals the effects, idempotency, late marks, marks changed before and after the batch, and the
 * N-19 dispatcher (one per booking, the class's own date, `noShowNotice.sentAt`). Also a club on Buenos Aires time.
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
    Map<String, Object> counters(JobRun run) {
        var map = new TreeMap<String, Object>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
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
        assertThat(thursday.items()).extracting(JobRun.Item::entityId).containsExactly(attendance(wed).getString("_id"));
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
        assertThat(dry.items()).extracting(JobRun.Item::action).containsOnly("WOULD_NOTIFY");
        assertThat(dry.items()).extracting(JobRun.Item::entityId).containsExactlyInAnyOrder(attendance(tue).getString("_id"), attendance(duna).getString("_id"),
                attendance(toby).getString("_id"), attendance(nit).getString("_id"));
        assertThat(dry.items().getFirst().detail()).extracting(JobRun.Entry::key).containsExactlyInAnyOrder("attendanceId", "bookingId", "memberId", "dogName", "classDate");
        assertThat(dry.items().getFirst().detail()).contains(new JobRun.Entry("bookingId", tue), new JobRun.Entry("memberId", "s08-m-c2"),
                new JobRun.Entry("dogName", "Dog2"), new JobRun.Entry("classDate", "2026-10-06"));
        // R-15-08: the dry run wrote nothing but its JobRun.
        assertThat(allEvents()).isEqualTo(eventsBefore);
        assertThat(attendance(duna).get("noShowNotice")).isNull();

        var run = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(run.trigger()).isEqualTo(JobTrigger.SCHEDULE); assertThat(run.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(run.scheduledFor()).isEqualTo(Instant.parse("2026-10-09T06:00:00Z")); assertThat(run.scheduledForLocal()).isEqualTo("2026-10-09T08:00");
        // The plan equals the effects (same items, same details), and the batch counters: 4 notices, 1 late (Tuesday's).
        assertThat(run.items()).extracting(JobRun.Item::entityId).containsExactlyElementsOf(dry.items().stream().map(JobRun.Item::entityId).toList());
        assertThat(run.items()).extracting(JobRun.Item::action).containsOnly("NOTIFY");
        for (int i = 0; i < run.items().size(); i++) {
            assertThat(run.items().get(i).detail()).containsExactlyInAnyOrderElementsOf(dry.items().get(i).detail());
        }
        assertThat(counters(run)).isEqualTo(Map.of("notices", 4L, "late", 1L));
        assertThat(run.parametersSnapshot()).contains(new JobRun.Entry("messaging.noShowNoticeTime", "08:00"), new JobRun.Entry("jobs.noShowNotices.enabled", true));
        var batches = eventsOf("NoShowNoticeDue");
        assertThat(batches).hasSize(2);
        var friday = batches.stream().filter(e -> e.get("payload", Document.class).getList("bookingIds", String.class).size() == 4).findFirst().orElseThrow();
        assertThat(friday.get("payload", Document.class).getList("bookingIds", String.class)).containsExactlyInAnyOrder(tue, duna, toby, nit);
        assertThat(friday.get("payload", Document.class).getList("attendanceIds", String.class)).containsExactlyInAnyOrderElementsOf(
                dry.items().stream().map(JobRun.Item::entityId).toList());
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
        var crowd = rows.stream().filter(n -> "APP".equals(n.getString("channel")) && tue.equals(n.get("subject", Document.class).getString("bookingId"))).findFirst().orElseThrow().get("variables", Document.class);
        assertThat(crowd.getString("class_date")).as("a late mark keeps its own class date").isEqualTo(fullDate(LocalDate.of(2026, 10, 6), "ca"));
        var mail = (FakeEmailSender) email;
        assertThat(mail.lastTo("s08-laura@example.test").subject()).isEqualTo("T'hem trobat a faltar");
        assertThat(mail.lastTo("s08-laura@example.test").text()).contains("No vas poder venir amb Duna a la classe de " + fullDate(LocalDate.of(2026, 10, 8), "ca")).doesNotContain("ahir");
        assertThat(mail.lastTo("s08-joan@example.test").subject()).isEqualTo("Te hemos echado de menos");
        assertThat(mail.lastTo("s08-joan@example.test").text()).contains("a la clase del " + fullDate(LocalDate.of(2026, 10, 8), "es")).doesNotContain("ayer");
        assertThat(mail.lastTo("s08-pere@example.test").subject()).isEqualTo("We missed you");
        assertThat(mail.lastTo("s08-pere@example.test").text()).contains("to the class on " + fullDate(LocalDate.of(2026, 10, 8), "en")).doesNotContain("yesterday");
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
        assertThat(run.items()).singleElement().satisfies(item -> assertThat(item.detail()).contains(new JobRun.Entry("classDate", "2026-10-08")));
        assertThat(counters(run)).isEqualTo(Map.of("notices", 1L, "late", 0L));
        dispatch();
        var rows = NotificationRows.rows(mongo, CLUB, "N-19").stream().filter(n -> "APP".equals(n.getString("channel"))).toList();
        assertThat(rows).singleElement().satisfies(n -> assertThat(n.get("variables", Document.class).getString("class_date")).isEqualTo(fullDate(LocalDate.of(2026, 10, 8), "ca")));
        System.out.println("E6-T04 no-show-notices Buenos Aires JobRun " + mongo.findById(run.id(), Document.class, "job_runs").toJson());
    }
}
