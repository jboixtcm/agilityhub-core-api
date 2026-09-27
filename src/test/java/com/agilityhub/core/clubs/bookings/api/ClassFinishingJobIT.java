package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.jobs.ClassFinishingJob;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import static org.assertj.core.api.Assertions.*;

/**
 * S15 R-15-18 P8 `class-finishing` (E6-T04: T-15-26) on the fictional S08 club: (a) the silent sweep of the waiting entries
 * of a started class, (b) a class 18:50–19:50 finishing on the 20:05 tick and not on the 20:04 one, the dry run equal to the
 * real effects and writing nothing, no business event, S06's notes-only edit and the sheet still markable until T1
 * (R-10-03); the switch with its one SKIPPED per hour and the manual run; a class on the October DST fold.
 */
class ClassFinishingJobIT extends BookingFixtures {
    @Autowired JobRunner runner; @Autowired ClassFinishingJob job;

    @BeforeEach void jobs() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(new Query(), "job_locks");
    }
    String id(JsonNode booking) { return booking.path("id").asText(); }
    Map<String, Long> counters(JobRun run) {
        var map = new TreeMap<String, Long>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
    List<String> eventTypesSince(long before) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB)).with(org.springframework.data.domain.Sort.by("occurredAt")), Document.class, "domain_events")
                .stream().skip(before).map(e -> e.getString("type")).toList();
    }
    long allEvents() { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "domain_events"); }

    @Test void T_15_26_aClassOf1850To1950FinishesOnThe2005TickAndTheWaitingEntriesOfAStartedClassGoSilently() throws Exception {
        // Wednesday 07-10 18:50–19:50, capacity 3: full, Crowd0 waiting, Crowd1 with an open offer; Thursday's waiting entry stays.
        String duna = id(book(as("laura"), "wed", "s08-d-duna")); book(as("joan"), "wed", "s08-d-toby"); book(as("pere"), "wed", "s08-d-nit");
        String waiting = join(as("c0"), "wed", "s08-d-c0", 201).path("id").asText();
        String offered = join(as("c1"), "wed", "s08-d-c1", 201).path("id").asText();
        for (int i = 2; i < 5; i++) { book(as("c" + i), "thu", "s08-d-c" + i); }
        String tomorrow = join(as("c5"), "thu", "s08-d-c5", 201).path("id").asText();
        dispatch();
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "notifications");
        // An offer still open when the class starts (the ALL_AT_ONCE claim window runs to the start).
        mongo.updateFirst(Query.query(Criteria.where("_id").is(offered)), new Update().set("state", "NOTIFIED").set("notifiedAt", NOW), "waitlist_entries");

        // 18:49 nothing has started; 18:50 the class starts: a dry run lists the two sweeps and writes nothing.
        clock.setInstant(local("2026-10-07T18:49"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).isEmpty();
        clock.setInstant(local("2026-10-07T18:50"));
        long before = allEvents();
        var drySweep = runner.manual(CLUB, JobName.CLASS_FINISHING, true, "s08-admin");
        assertThat(drySweep.items()).extracting(JobRun.Item::entityId, JobRun.Item::action)
                .containsExactlyInAnyOrder(tuple(waiting, "WOULD_SWEEP"), tuple(offered, "WOULD_SWEEP"));
        assertThat(drySweep.items()).allSatisfy(item -> assertThat(item.detail()).contains(new JobRun.Entry("classId", "s08-wed")));
        assertThat(entry(waiting).getString("state")).isEqualTo("ACTIVE"); assertThat(entry(offered).getString("state")).isEqualTo("NOTIFIED");
        assertThat(allEvents()).isEqualTo(before);
        var sweep = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(sweep.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(sweep.items()).extracting(JobRun.Item::entityId).containsExactlyElementsOf(drySweep.items().stream().map(JobRun.Item::entityId).toList());
        assertThat(sweep.items()).extracting(JobRun.Item::action).containsOnly("SWEEP");
        assertThat(counters(sweep)).isEqualTo(Map.of("swept", 2L));
        for (String entry : List.of(waiting, offered)) {
            assertThat(entry(entry).getString("state")).isEqualTo("CANCELLED"); assertThat(entry(entry).getString("cancelReason")).isEqualTo("CLASS_STARTED");
        }
        assertThat(entry(tomorrow).getString("state")).isEqualTo("ACTIVE");
        assertThat(session("wed").get("counters", Document.class)).containsEntry("waiting", 0);
        assertThat(session("wed").getString("state")).isEqualTo("ACTIVE");
        // Silently (S08 R-08-16): no business event and no notification, only the framework's SchedulerRun.
        assertThat(eventTypesSince(before)).containsOnly("SchedulerRun");
        dispatch();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "notifications")).isZero();

        // 20:04 the class is still ACTIVE; the 20:05 tick finishes it (endsAt + classes.finishGraceMinutes).
        clock.setInstant(local("2026-10-07T20:04"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).isEmpty();
        assertThat(session("wed").getString("state")).isEqualTo("ACTIVE");
        clock.setInstant(local("2026-10-07T20:05"));
        before = allEvents();
        var dryFinish = runner.manual(CLUB, JobName.CLASS_FINISHING, true, "s08-admin");
        assertThat(dryFinish.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple("s08-wed", "WOULD_FINISH"));
        assertThat(dryFinish.items().getFirst().detail()).containsExactly(new JobRun.Entry("classId", "s08-wed"));
        assertThat(session("wed").getString("state")).isEqualTo("ACTIVE"); assertThat(allEvents()).isEqualTo(before);
        var finish = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(finish.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple("s08-wed", "FINISH"));
        assertThat(finish.items().getFirst().detail()).isEqualTo(dryFinish.items().getFirst().detail());
        assertThat(counters(finish)).isEqualTo(Map.of("finished", 1L));
        assertThat(finish.parametersSnapshot()).contains(new JobRun.Entry("classes.finishGraceMinutes", 15), new JobRun.Entry("jobs.classFinishing.enabled", true));
        assertThat(session("wed").getString("state")).isEqualTo("FINISHED");
        assertThat(session("wed").getDate("finishedAt").toInstant()).isEqualTo(local("2026-10-07T20:05"));
        assertThat(session("thu").getString("state")).isEqualTo("ACTIVE");
        assertThat(eventTypesSince(before)).as("no business event (R-15-18)").containsOnly("SchedulerRun");
        System.out.println("E6-T04 class-finishing JobRun dry  " + mongo.findById(dryFinish.id(), Document.class, "job_runs").toJson());
        System.out.println("E6-T04 class-finishing JobRun real " + mongo.findById(finish.id(), Document.class, "job_runs").toJson());
        // Idempotent by state: the next tick and a manual run find nothing.
        clock.setInstant(local("2026-10-07T20:06"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).isEmpty();
        assertThat(runner.manual(CLUB, JobName.CLASS_FINISHING, false, "s08-admin").items()).isEmpty();

        // S06: once FINISHED only `notes` is editable (409 INVALID_STATE otherwise).
        long version = ((Number) session("wed").get("version")).longValue();
        assertThat(code(call(HttpMethod.PATCH, "/class-sessions/s08-wed", Map.of("version", version, "capacity", 4), as("admin"), 409))).isEqualTo("INVALID_STATE");
        call(HttpMethod.PATCH, "/class-sessions/s08-wed", Map.of("version", version, "notes", "Bona sessió (fictici)"), as("admin"), 200);
        // R-10-03: the sheet keeps working on the FINISHED class until T1.
        clock.setInstant(local("2026-10-07T20:10"));
        var sheet = call(HttpMethod.GET, "/class-sessions/s08-wed/attendance", null, as("inst"), 200);
        assertThat(sheet.path("sheet").path("canMarkPresence").asBoolean()).isTrue();
        var saved = call(HttpMethod.PUT, "/class-sessions/s08-wed/attendance", Map.of("version", sheet.path("sheet").path("version").asLong(),
                "items", List.of(Map.of("bookingId", duna, "state", "PRESENT"))), as("inst"), 200, UUID.randomUUID().toString());
        assertThat(saved.path("applied").get(0).asText()).isEqualTo(duna);
    }

    @Test void T_15_26_R_15_03_theSwitchOffRecordsOneSkippedRunPerHourAndTheAdminStillRunsIt() throws Exception {
        book(as("laura"), "wed", "s08-d-duna");
        parameter("jobs.classFinishing.enabled", false);
        clock.setInstant(local("2026-10-07T20:05"));
        var skipped = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(skipped.status()).isEqualTo(JobStatus.SKIPPED); assertThat(skipped.skipReason()).isEqualTo(SkipReason.DISABLED);
        for (int minute = 1; minute <= 59; minute++) {
            assertThat(runner.scheduled(CLUB, true, job, clock.instant().plus(Duration.ofMinutes(minute)))).as("minute " + minute).isEmpty();
        }
        assertThat(runner.scheduled(CLUB, true, job, clock.instant().plus(Duration.ofMinutes(61))).orElseThrow().skipReason()).isEqualTo(SkipReason.DISABLED);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("job").is("CLASS_FINISHING")), "job_runs")).isEqualTo(2);
        assertThat(session("wed").getString("state")).isEqualTo("ACTIVE");
        // R-15-09: [Executa ara] runs with the switch off, audited as JOB_TRIGGERED.
        var run = call(HttpMethod.POST, "/jobs/class-finishing/trigger", Map.of("dryRun", false), as("admin"), 200);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.at("/effects/counters/finished").asLong()).isEqualTo(1);
        assertThat(session("wed").getString("state")).isEqualTo("FINISHED");
        assertThat(count("audit_entries", Criteria.where("action").is("JOB_TRIGGERED"))).isEqualTo(1);
    }

    /**
     * R-15-02 / R-06-14: Sunday 25-10-2026 the clocks go back at 03:00 CEST. A class 01:45–02:45 ends at the first 02:45
     * (00:45Z); continuous ticks are plain instants, so it finishes at 01:00Z (the second 02:00, CET), not an hour later.
     */
    @Test void T_15_26_R_15_02_aClassOnTheOctoberFoldFinishesFifteenMinutesAfterItsFirstEnd() {
        session("fold", "2026-10-25T01:45", 3, List.of());
        assertThat(session("fold").getDate("endsAt").toInstant()).isEqualTo(Instant.parse("2026-10-25T00:45:00Z"));
        clock.setInstant(Instant.parse("2026-10-25T00:59:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).extracting(JobRun.Item::entityId).doesNotContain("s08-fold");
        clock.setInstant(Instant.parse("2026-10-25T01:00:00Z"));
        var run = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(run.scheduledFor()).isEqualTo(Instant.parse("2026-10-25T01:00:00Z"));
        assertThat(run.scheduledForLocal()).isEqualTo("2026-10-25T02:00");
        assertThat(run.items()).extracting(JobRun.Item::entityId).contains("s08-fold");
        assertThat(session("fold").getString("state")).isEqualTo("FINISHED");
    }
}
