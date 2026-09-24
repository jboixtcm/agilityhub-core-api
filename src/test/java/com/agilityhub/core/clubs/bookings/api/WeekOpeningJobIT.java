package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.common.application.WeekOpeningJob;
import com.agilityhub.core.clubs.scheduling.domain.SchedulingEvent;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;

/**
 * S15 R-15-11 P1 `week-opening` (T-15-11, T-15-12): Sunday 04-10-2026 20:00 Madrid (18:00Z) opens the booking week
 * `2026-10-11` whose ISO week starts on Monday 12-10. Audience of N-33: Laura, Joan, Pere and the twenty crowd members
 * (ACTIVE with an ACTIVE dog); a PENDING and a LEFT member with dogs and an ACTIVE member whose only dog is INACTIVE are not.
 */
class WeekOpeningJobIT extends BookingFixtures {
    static final Instant OPENS = Instant.parse("2026-10-04T18:00:00Z");
    @Autowired JobRunner runner;
    @Autowired WeekOpeningJob job;
    @Autowired JobAdminService admin;
    @Autowired com.agilityhub.core.clubs.training.application.TrainingGridCache grids;

    @BeforeEach void weeks() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "weeks");
        mongo.remove(new Query(), "job_locks");
        parameter("messaging.notifyWeekOpening", true);
        for (var entry : Map.of("pending", "PENDING", "left", "LEFT", "idle", "ACTIVE").entrySet()) {
            account(entry.getKey(), "MEMBER", "s08-m-" + entry.getKey()); member("s08-m-" + entry.getKey(), entry.getKey(), entry.getKey(), "es");
            mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-" + entry.getKey())), new Update().set("status", entry.getValue()), "members");
            dog("s08-d-" + entry.getKey(), "s08-m-" + entry.getKey(), "Dog " + entry.getKey(), "C", "MALE");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-idle")), new Update().set("status", "INACTIVE"), "dogs");
        clock.setInstant(OPENS);
    }
    /** The ISO week of 12-10 with the Monday classes in the given state. */
    private void week(String id, String start, String state, String sessionState, String... sessions) {
        var monday = java.time.LocalDate.parse(start);
        mongo.insert(new Document("_id", id).append("clubId", CLUB).append("isoYear", 2026).append("isoWeek", monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                .append("startDate", start).append("endDate", monday.plusDays(6).toString()).append("state", state).append("version", 0L), "weeks");
        for (String session : sessions) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-" + session)), new Update().set("weekId", id).set("state", sessionState), "class_sessions");
        }
    }
    /** The S17 health of this club's week-opening cell. */
    private JobViews.JobHealth health() {
        return admin.overview(CLUB, null).clubs().getFirst().jobs().stream().filter(cell -> cell.name().equals("week-opening"))
                .findFirst().orElseThrow().health();
    }
    private List<Document> n33(String channel) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-33").and("channel").is(channel)), Document.class, "notifications");
    }

    @Test void T_15_11_theOpeningRecordsTheWeekEmitsWeekOpenedAndQueuesN33OnceForTheRightMembers() throws Exception {
        week("s08-week-42", "2026-10-12", "VALIDATED", "ACTIVE", "mon", "mon2");
        var run = runner.scheduled(CLUB, true, job, OPENS).orElseThrow();
        assertThat(run.trigger()).isEqualTo(JobTrigger.SCHEDULE);
        assertThat(run.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(run.scheduledForLocal()).isEqualTo("2026-10-04T20:00");
        assertThat(run.items()).singleElement().satisfies(item -> {
            assertThat(item.entityId()).isEqualTo("2026-10-11"); assertThat(item.action()).isEqualTo("OPEN");
            assertThat(item.detail()).contains(new JobRun.Entry("activeClasses", 2), new JobRun.Entry("recipients", 23));
        });
        assertThat(run.counters()).contains(new JobRun.Entry("opened", 1L), new JobRun.Entry("notified", 23L));
        assertThat(eventsOf("WeekOpened")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("openedWeekKey", "2026-10-11").containsEntry("isoWeekStart", "2026-10-12").containsEntry("currentWeekKey", "2026-10-04")
                .containsEntry("opensAt", "2026-10-04T18:00:00Z").containsEntry("notified", true));
        assertThat(eventsOf("TrainingCounterReset")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("weekStart", "2026-10-04T18:00:00Z"));
        var week = mongo.findById("s08-week-42", Document.class, "weeks");
        assertThat(week.getDate("openedAt").toInstant()).isEqualTo(OPENS);
        assertThat(week.get("openingNotifiedAt")).isNotNull();
        dispatch();
        assertThat(n33("APP")).hasSize(23).extracting(n -> n.getString("accountId")).contains("s08-laura", "s08-joan", "s08-pere", "s08-c0")
                .doesNotContain("s08-pending", "s08-left", "s08-idle", "s08-admin", "s08-inst");
        assertThat(n33("PUSH")).hasSize(23).allSatisfy(n -> assertThat(n.getString("status")).isEqualTo("QUEUED"));
        var laura = n33("APP").stream().filter(n -> n.getString("accountId").equals("s08-laura")).findFirst().orElseThrow();
        assertThat(laura.get("variables", Document.class).getString("week_start")).contains("12").contains("octubre");
        assertThat(laura.get("variables", Document.class)).containsEntry("action", "OPEN_BOOKING");
        // A second execution produces no effect: no new event, no new row.
        var again = runner.manual(CLUB, JobName.WEEK_OPENING, false, "s08-admin");
        assertThat(again.items()).isEmpty();
        assertThat(again.counters()).contains(new JobRun.Entry("alreadyOpened", 1L));
        dispatch();
        assertThat(eventsOf("WeekOpened")).hasSize(1);
        assertThat(n33("APP")).hasSize(23);
    }

    @Test void T_15_11_withoutTheNoticeOrFreeTrainingOnlyTheOpeningHappens() {
        week("s08-week-42", "2026-10-12", "VALIDATED", "ACTIVE", "mon", "mon2");
        parameter("messaging.notifyWeekOpening", false);
        modules(Module.WAITLIST, Module.PACKS, Module.SMS, Module.PUSH);
        // E5-T09 (E5-T05 review #6): R-15-11 step (1) drops the grid caches in P1's own transaction, also with FREE_TRAINING off.
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<com.agilityhub.core.clubs.training.application.TrainingGridCache.Day> loader = () -> {
            loads.incrementAndGet(); return new com.agilityhub.core.clubs.training.application.TrainingGridCache.Day(java.time.LocalDate.parse("2026-10-05"), false, List.of(), List.of(), List.of());
        };
        grids.day(CLUB, java.time.LocalDate.parse("2026-10-05"), loader); grids.day(OTHER, java.time.LocalDate.parse("2026-10-05"), loader);
        grids.day(CLUB, java.time.LocalDate.parse("2026-10-05"), loader);
        assertThat(loads.get()).as("warm").isEqualTo(2);
        var run = runner.scheduled(CLUB, true, job, OPENS).orElseThrow();
        assertThat(run.counters()).contains(new JobRun.Entry("notified", 0L));
        assertThat(eventsOf("WeekOpened")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("notified", false));
        assertThat(eventsOf("TrainingCounterReset")).isEmpty();
        grids.day(CLUB, java.time.LocalDate.parse("2026-10-05"), loader);
        assertThat(loads.get()).as("the club's grid was dropped before any consumer ran").isEqualTo(3);
        grids.day(OTHER, java.time.LocalDate.parse("2026-10-05"), loader);
        assertThat(loads.get()).as("another club's grid is kept").isEqualTo(3);
        dispatch();
        assertThat(n33("APP")).isEmpty();
        assertThat(mongo.findById("s08-week-42", Document.class, "weeks").get("openingNotifiedAt")).isNull();
    }

    @Test void T_15_12_aWeekValidatedAfterTheOpeningGetsItsDeferredNoticeOnce() {
        week("s08-week-42", "2026-10-12", "GENERATED", "DRAFT", "mon", "mon2");
        var run = runner.scheduled(CLUB, true, job, OPENS).orElseThrow();
        assertThat(run.items().getFirst().detail()).contains(new JobRun.Entry("activeClasses", 0), new JobRun.Entry("recipients", 0));
        assertThat(eventsOf("WeekOpened").getFirst().get("payload", Document.class)).containsEntry("notified", false);
        assertThat(mongo.findById("s08-week-42", Document.class, "weeks").get("openingNotifiedAt")).isNull();
        dispatch();
        assertThat(n33("APP")).isEmpty();
        // Monday 9:00 the admin validates the week → the deferred N-33 goes out then, once.
        clock.setInstant(local("2026-10-05T09:00"));
        mongo.updateMulti(Query.query(Criteria.where("weekId").is("s08-week-42")), new Update().set("state", "ACTIVE"), "class_sessions");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-week-42")), new Update().set("state", "VALIDATED"), "weeks");
        var validated = new SchedulingEvent(SchedulingEvent.Kind.WeekValidated, CLUB, "s08-week-42", clock.instant(),
                Map.of("weekId", "s08-week-42", "classIds", List.of("s08-mon", "s08-mon2")), "s08-admin", null, DomainEvent.Origin.BACKOFFICE);
        publish(validated);
        dispatch();
        assertThat(n33("APP")).hasSize(23);
        assertThat(mongo.findById("s08-week-42", Document.class, "weeks").getDate("openingNotifiedAt").toInstant()).isEqualTo(local("2026-10-05T09:00"));
        publish(validated);
        dispatch();
        assertThat(n33("APP")).hasSize(23);
        // A week validated before its booking opens gets nothing now (P1 will notify when it opens).
        week("s08-week-43", "2026-10-19", "VALIDATED", "ACTIVE", "later");
        publish(new SchedulingEvent(SchedulingEvent.Kind.WeekValidated, CLUB, "s08-week-43", clock.instant(),
                Map.of("weekId", "s08-week-43", "classIds", List.of("s08-later")), "s08-admin", null, DomainEvent.Origin.BACKOFFICE));
        dispatch();
        assertThat(n33("APP")).hasSize(23);
        assertThat(mongo.findById("s08-week-43", Document.class, "weeks").get("openingNotifiedAt")).isNull();
    }

    @Test void T_15_04_T_15_06_theOpeningCatchesUpForADayThePlanMatchesAndNothingIsLostWhenMissed() {
        week("s08-week-42", "2026-10-12", "VALIDATED", "ACTIVE", "mon", "mon2");
        // 26 h late (Monday 20:00Z): MISSED_WINDOW, no business effect lost (W0/W1 are functions of time). The club has no
        // week-opening history, so this is the E33 baseline: no JobFailed, and D11/S17 read the process as never executed.
        var missed = runner.scheduled(CLUB, true, job, Instant.parse("2026-10-05T20:00:00Z")).orElseThrow();
        assertThat(missed.skipReason()).isEqualTo(SkipReason.MISSED_WINDOW);
        assertThat(eventsOf("JobFailed")).isEmpty();
        assertThat(health()).isEqualTo(JobViews.JobHealth.OK);
        // The next Sunday's opening is missed too: now it is a lost run, with JobFailed (→ N-42) and a red S17 cell.
        var again = runner.scheduled(CLUB, true, job, Instant.parse("2026-10-12T20:00:00Z")).orElseThrow();
        assertThat(again.skipReason()).isEqualTo(SkipReason.MISSED_WINDOW);
        assertThat(eventsOf("JobFailed")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("runId", again.id()));
        assertThat(health()).isEqualTo(JobViews.JobHealth.ALERT);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        // 3 h late: CATCH_UP; the dry run plans exactly what the real run then does.
        clock.setInstant(Instant.parse("2026-10-04T21:00:00Z"));
        var dry = runner.manual(CLUB, JobName.WEEK_OPENING, true, "s08-admin");
        assertThat(dry.items()).extracting(JobRun.Item::action).containsExactly("WOULD_OPEN");
        assertThat(eventsOf("WeekOpened")).isEmpty();
        assertThat(mongo.findById("s08-week-42", Document.class, "weeks").get("openedAt")).isNull();
        var late = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(late.trigger()).isEqualTo(JobTrigger.CATCH_UP);
        assertThat(late.scheduledFor()).isEqualTo(OPENS);
        assertThat(late.items()).extracting(JobRun.Item::entityId).containsExactlyElementsOf(dry.items().stream().map(JobRun.Item::entityId).toList());
        assertThat(late.items()).extracting(JobRun.Item::detail).containsExactlyElementsOf(dry.items().stream().map(JobRun.Item::detail).toList());
        System.out.println("E5-T05 week-opening JobRun dry  " + mongo.findById(dry.id(), Document.class, "job_runs").toJson());
        System.out.println("E5-T05 week-opening JobRun real " + mongo.findById(late.id(), Document.class, "job_runs").toJson());
    }
}
