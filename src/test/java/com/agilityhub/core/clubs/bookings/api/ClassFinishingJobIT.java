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
 * (R-10-03); the switch with its one SKIPPED per hour and the manual run; a class on the October DST fold. Round 2: the
 * sweep by the class's own start (a moved class) and no sweep without the WAITLIST module.
 */
class ClassFinishingJobIT extends BookingFixtures {
    @Autowired JobRunner runner; @Autowired ClassFinishingJob job; @Autowired com.agilityhub.core.clubs.bookings.application.WaitlistService waitlist;

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
     * E6-T04 round 2 (review #1, ruling E65; S15 R-15-18a, S08 R-08-16): the sweep decides by the class's own start in S06,
     * never by the copy a waiting entry keeps. The admin moves Wednesday's class from 18:50 to 19:50 and Thursday's from
     * 18:50 to 16:00; the `ClassSessionUpdated` consumer that refreshes the entries has not run yet (the outbox is not
     * delivered). At 18:55 Wednesday's entry stays ACTIVE — also when a stale plan asks for it —, the 19:50 tick sweeps it,
     * and Thursday's entry goes at 16:00, not at 18:50.
     */
    @Test void T_15_26_R_15_18_theSweepDecidesByTheClassesOwnStartNotTheEntrysCopy() throws Exception {
        book(as("laura"), "wed", "s08-d-duna"); book(as("joan"), "wed", "s08-d-toby"); book(as("pere"), "wed", "s08-d-nit");
        String wednesday = join(as("c0"), "wed", "s08-d-c0", 201).path("id").asText();
        for (int i = 1; i < 4; i++) { book(as("c" + i), "thu", "s08-d-c" + i); }
        String thursday = join(as("c4"), "thu", "s08-d-c4", 201).path("id").asText();
        dispatch();
        // (The fixture's classes have no level; S06 validates a moved class like any edit, so the move names the dogs' level C.)
        call(HttpMethod.PATCH, "/class-sessions/s08-wed", Map.of("version", ((Number) session("wed").get("version")).longValue(), "startTime", "19:50", "endTime", "20:50",
                "levelIds", List.of("s08-lv-C")), as("admin"), 200);
        call(HttpMethod.PATCH, "/class-sessions/s08-thu", Map.of("version", ((Number) session("thu").get("version")).longValue(), "startTime", "16:00", "endTime", "17:00",
                "levelIds", List.of("s08-lv-C")), as("admin"), 200);
        assertThat(session("wed").getDate("startsAt").toInstant()).isEqualTo(local("2026-10-07T19:50"));
        assertThat(session("thu").getDate("startsAt").toInstant()).isEqualTo(local("2026-10-08T16:00"));
        // The consumer is delayed: both ClassSessionUpdated wait in the outbox, and the entries keep the old start.
        assertThat(count("domain_events", Criteria.where("type").is("ClassSessionUpdated").and("status").is("PENDING"))).isEqualTo(2);
        assertThat(entry(wednesday).getDate("classStartsAt").toInstant()).isEqualTo(local("2026-10-07T18:50"));
        assertThat(entry(thursday).getDate("classStartsAt").toInstant()).isEqualTo(local("2026-10-08T18:50"));

        clock.setInstant(local("2026-10-07T18:55"));
        var dry = runner.manual(CLUB, JobName.CLASS_FINISHING, true, "s08-admin");
        assertThat(dry.items()).extracting(JobRun.Item::entityId).doesNotContain(wednesday, thursday);
        var early = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(early.items()).extracting(JobRun.Item::entityId).doesNotContain(wednesday, thursday);
        assertThat(entry(wednesday).getString("state")).isEqualTo("ACTIVE");
        // A plan made from the stale copy is refused inside the sweep's transaction too.
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            assertThat(waitlist.sweepStarted(wednesday, clock.instant())).isFalse();
            assertThat(waitlist.sweepStarted(clock.instant())).isZero();
        }
        assertThat(entry(wednesday).getString("state")).isEqualTo("ACTIVE"); assertThat(entry(wednesday).get("cancelReason")).isNull();

        // 19:50, the class's new start: swept, although the entry still says 18:50.
        clock.setInstant(local("2026-10-07T19:50"));
        var swept = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(swept.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple(wednesday, "SWEEP"));
        assertThat(entry(wednesday).getString("state")).isEqualTo("CANCELLED"); assertThat(entry(wednesday).getString("cancelReason")).isEqualTo("CLASS_STARTED");

        // Thursday's class moved earlier: nothing at 15:59, swept at 16:00 although its entry still says 18:50.
        clock.setInstant(local("2026-10-08T15:59"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).extracting(JobRun.Item::entityId).doesNotContain(thursday);
        clock.setInstant(local("2026-10-08T16:00"));
        assertThat(entry(thursday).getDate("classStartsAt").toInstant()).isEqualTo(local("2026-10-08T18:50"));
        var moved = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(moved.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple(thursday, "SWEEP"));
        assertThat(entry(thursday).getString("state")).isEqualTo("CANCELLED"); assertThat(entry(thursday).getString("cancelReason")).isEqualTo("CLASS_STARTED");
    }

    /**
     * E6-T04 round 2 (review #2, ruling E65; S15 R-15-03, §9): without the WAITLIST module P8 leaves its waiting-list step
     * out — no `SWEEP` in the plan, the dry run or the run, and the live entries of a started class stay as they were —
     * while the class still finishes.
     */
    @Test void T_15_26_R_15_03_withoutTheWaitlistModuleP8SweepsNothingAndStillFinishesTheClass() throws Exception {
        book(as("laura"), "wed", "s08-d-duna"); book(as("joan"), "wed", "s08-d-toby"); book(as("pere"), "wed", "s08-d-nit");
        String waiting = join(as("c0"), "wed", "s08-d-c0", 201).path("id").asText();
        String offered = join(as("c1"), "wed", "s08-d-c1", 201).path("id").asText();
        dispatch();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(offered)), new Update().set("state", "NOTIFIED").set("notifiedAt", NOW), "waitlist_entries");
        var before = List.of(entry(waiting), entry(offered));
        modules(Arrays.stream(com.agilityhub.core.platform.application.Module.values()).filter(m -> m != com.agilityhub.core.platform.application.Module.WAITLIST)
                .toArray(com.agilityhub.core.platform.application.Module[]::new));

        clock.setInstant(local("2026-10-07T18:50"));
        assertThat(runner.manual(CLUB, JobName.CLASS_FINISHING, true, "s08-admin").items()).extracting(JobRun.Item::action).doesNotContain("WOULD_SWEEP");
        var started = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(started.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(started.items()).extracting(JobRun.Item::action).doesNotContain("SWEEP");
        assertThat(counters(started)).doesNotContainKey("swept");

        clock.setInstant(local("2026-10-07T20:05"));
        var finish = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(finish.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple("s08-wed", "FINISH"));
        assertThat(session("wed").getString("state")).isEqualTo("FINISHED");
        assertThat(List.of(entry(waiting), entry(offered))).as("the entries stay exactly as they were").isEqualTo(before);
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
