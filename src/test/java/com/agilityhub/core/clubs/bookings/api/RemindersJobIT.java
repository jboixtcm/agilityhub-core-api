package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.ClassReminders;
import com.agilityhub.core.clubs.census.application.ReminderLeads;
import com.agilityhub.core.clubs.common.application.RemindersJob;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.support.NotificationRows;
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
 * S15 R-15-14 P4 `reminders` (E7-T04: T-15-17, T-15-18) on the fictional S08 club, by advancing the injected clock minute by
 * minute: Laura chooses 2 h and books Thursday's 18:50 class on Tuesday → `ReminderDue` at the 16:50 tick and never before,
 * `reminderSentAt`, nothing at the next tick, the S11 engine's N-13 (APP, PUSH without a subscription, EMAIL off by
 * `OPERATIONAL`); a booking made at 17:30 for 18:50, a member without a lead and a booking the club cancelled get none; a
 * training booking with FREE_TRAINING gets N-13 with its ring (none without the module). T-15-18: a preference changed at
 * 10:00 applies at the 10:00 tick both ways, and the DST day of 25-10-2026 in Madrid and Buenos Aires is plain instant
 * arithmetic. The dry run plans exactly what the run then does, and writes nothing. Round 2: the other club's booking due at
 * the same 16:50 is never this club's (AGENTS rule 4), and a training reminder's aggregate is `TrainingBooking` (E82).
 */
class RemindersJobIT extends BookingFixtures {
    @Autowired JobRunner runner; @Autowired RemindersJob job; @Autowired ClassReminders classReminders; @Autowired ReminderLeads leads;

    @BeforeEach void jobs() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(new Query(), "job_locks");
    }
    Map<String, Long> counters(JobRun run) {
        var map = new TreeMap<String, Long>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
    Optional<JobRun> tick(String local) {
        clock.setInstant(local(local));
        return runner.scheduled(CLUB, true, job, clock.instant());
    }
    void lead(String memberId, Integer minutes) {
        var preferences = new Document("reminderMinutesBefore", minutes);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(memberId)), new Update().set("notificationPreferences", preferences), "members");
    }
    /** A booking stored as S08 stores it (the cases whose path to the booking is not what they prove). */
    String stored(String id, String classId, String dogId, String memberId, String state, Instant bookedAt) {
        var session = session(classId);
        mongo.insert(new Document("_id", id).append("clubId", CLUB).append("classSessionId", "s08-" + classId).append("dogId", dogId).append("memberId", memberId)
                .append("state", state).append("origin", "APP").append("bookedAt", Date.from(bookedAt))
                .append("classStartsAt", session.getDate("startsAt")).append("classEndsAt", session.getDate("endsAt"))
                .append("bookingWeekKey", "2026-10-04").append("version", 0L), "bookings");
        return id;
    }
    List<Document> reminders() { return eventsOf("ReminderDue"); }
    List<String> remindedBookings() { return reminders().stream().map(e -> e.get("payload", Document.class)).map(p -> Objects.toString(p.get("bookingId"), p.getString("trainingBookingId"))).toList(); }

    @Test void T_15_17_lauraChooses2HoursAndThursdays1850ClassBookedOnTuesdayIsRemindedAtThe1650TickOnly() throws Exception {
        // Laura chooses «2 h abans» in 12 (R-11-04) and books Thursday 08-10 18:50 with Duna on Tuesday 10:00.
        call(HttpMethod.PUT, "/me/notification-preferences", Map.of("reminderMinutesBefore", 120), as("laura"), 200);
        String duna = book(as("laura"), "thu", "s08-d-duna").path("id").asText();
        // Joan (no lead, «Mai») books the same class; Pere (2 h) books Friday's class, which the club then cancels.
        String toby = book(as("joan"), "thu", "s08-d-toby").path("id").asText();
        lead("s08-m-pere", 120);
        String nit = book(as("pere"), "fri", "s08-d-nit").path("id").asText();
        // AGENTS rule 4 (E7-T04 round 2): the other club has a member with 2 h and a booking for Thursday 18:50 too, due at 16:50.
        String elsewhere = otherClubsBookingDueAt1650();
        dispatch();
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "notifications");
        // Both reads P4 makes in this club see only this club: S08's scope and the census leads.
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            assertThat(classReminders.scope(local("2026-10-08T16:50"), local("2026-10-09T16:50"))).extracting(ClassReminders.Reminder::bookingId)
                    .contains(duna).doesNotContain(elsewhere);
            assertThat(leads.of(List.of("s08-m-laura", "s08-m-elsewhere"))).containsOnlyKeys("s08-m-laura");
        }

        // 16:49: not yet (16:50 > now).
        assertThat(tick("2026-10-08T16:49").orElseThrow().items()).isEmpty();
        assertThat(reminders()).isEmpty();
        // 16:50: the dry run plans exactly Duna's booking and writes nothing.
        clock.setInstant(local("2026-10-08T16:50"));
        var dry = runner.manual(CLUB, JobName.REMINDERS, true, "s08-admin");
        assertThat(dry.items()).extracting(JobRun.Item::entityType, JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple("Booking", duna, "WOULD_REMIND"));
        assertThat(dry.items().getFirst().detail()).containsExactlyInAnyOrder(new JobRun.Entry("bookingId", duna), new JobRun.Entry("memberId", "s08-m-laura"),
                new JobRun.Entry("startsAt", local("2026-10-08T18:50").toString()), new JobRun.Entry("lead", 120));
        assertThat(reminders()).isEmpty(); assertThat(booking(duna).get("reminderSentAt")).isNull();
        // The 16:50 tick: one ReminderDue, the mark, the counters; the plan equals the effects (T-15-06).
        var run = tick("2026-10-08T16:50").orElseThrow();
        assertThat(run.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(run.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple(duna, "REMIND"));
        assertThat(run.items().getFirst().detail()).isEqualTo(dry.items().getFirst().detail());
        assertThat(counters(run)).isEqualTo(Map.of("classReminders", 1L));
        assertThat(run.parametersSnapshot()).contains(new JobRun.Entry("jobs.reminders.enabled", true));
        assertThat(booking(duna).getDate("reminderSentAt").toInstant()).isEqualTo(local("2026-10-08T16:50"));
        var event = reminders().getFirst();
        assertThat(event.getString("aggregateId")).isEqualTo(duna); assertThat(event.getString("origin")).isEqualTo("SYSTEM");
        assertThat(event.getString("aggregateType")).isEqualTo("Booking");
        // This club's dry run and run neither planned, nor marked, nor announced the other club's booking (its own run does, below).
        assertThat(mongo.findById(elsewhere, Document.class, "bookings").get("reminderSentAt")).isNull();
        assertThat(remindersOf(OTHER)).isEmpty();
        assertThat(event.get("payload", Document.class)).containsEntry("bookingId", duna).containsEntry("memberId", "s08-m-laura")
                .containsEntry("dogId", "s08-d-duna").containsEntry("startsAt", local("2026-10-08T18:50").toString());
        // The next ticks and a manual run: nothing new (reminderSentAt).
        assertThat(tick("2026-10-08T16:51").orElseThrow().items()).isEmpty();
        clock.setInstant(local("2026-10-08T16:52"));
        assertThat(runner.manual(CLUB, JobName.REMINDERS, false, "s08-admin").items()).isEmpty();
        assertThat(reminders()).hasSize(1);
        // The S11 engine: N-13 to Laura in Catalan, APP + PUSH (no subscription) + EMAIL skipped by OPERATIONAL off.
        dispatch();
        var rows = NotificationRows.rows(mongo, CLUB, "N-13");
        assertThat(rows).extracting(r -> r.getString("memberId")).containsOnly("s08-m-laura");
        assertThat(rows).extracting(r -> r.getString("channel") + ":" + r.getString("status"))
                .containsExactlyInAnyOrder("APP:DELIVERED", "PUSH:SKIPPED_NO_CONTACT", "EMAIL:SKIPPED_BY_PREFERENCE");
        assertThat(rows.getFirst().getString("dedupKey")).isEqualTo("N-13:" + duna);
        assertThat(rows.getFirst().getString("title")).isEqualTo("Recordatori de classe");
        assertThat(rows.getFirst().get("action", Document.class).getString("type")).isEqualTo("OPEN_BOOKING");

        // Rock booked at 17:30 for 18:50: its reminder moment (16:50) is before the booking → never (N-04 confirmed it).
        clock.setInstant(local("2026-10-08T17:30"));
        String rock = book(as("laura"), "thu", "s08-d-rock").path("id").asText();
        assertThat(tick("2026-10-08T17:31").orElseThrow().items()).isEmpty();
        assertThat(tick("2026-10-08T18:49").orElseThrow().items()).isEmpty();
        // Joan has no lead: never. Friday 09-10 20:00: the club cancels Pere's class booking before 18:00 → out of scope.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(nit)), new Update().set("state", "CANCELLED_BY_CLUB"), "bookings");
        assertThat(tick("2026-10-09T18:00").orElseThrow().items()).isEmpty();
        assertThat(tick("2026-10-09T19:59").orElseThrow().items()).isEmpty();
        assertThat(remindedBookings()).containsExactly(duna).doesNotContain(rock, toby, nit, elsewhere);
        assertThat(booking(rock).get("reminderSentAt")).isNull(); assertThat(booking(toby).get("reminderSentAt")).isNull();
        assertThat(booking(elsewhere).get("reminderSentAt")).isNull();
        // The control: at the same 16:50 the other club's own run reminds its booking, and only that one.
        clock.setInstant(local("2026-10-08T16:50"));
        var theirs = runner.scheduled(OTHER, true, job, clock.instant()).orElseThrow();
        assertThat(theirs.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple(elsewhere, "REMIND"));
        assertThat(booking(elsewhere).get("reminderSentAt")).isNotNull();
        assertThat(remindersOf(OTHER)).singleElement().satisfies(e -> assertThat(e.getString("aggregateId")).isEqualTo(elsewhere));
        assertThat(remindedBookings()).containsExactly(duna);
    }

    /** The other club: a member with «2 h abans» and an ACTIVE booking (made on Tuesday) for its Thursday 18:50 class. */
    String otherClubsBookingDueAt1650() {
        mongo.save(new Document("_id", "s08-m-elsewhere").append("clubId", OTHER).append("firstName", "Elsewhere").append("lastName1", "Example").append("status", "ACTIVE")
                .append("bookingBlock", new Document("active", false)).append("notificationPreferences", new Document("reminderMinutesBefore", 120)).append("version", 0), "members");
        mongo.save(new Document("_id", "s08-d-elsewhere").append("clubId", OTHER).append("memberId", "s08-m-elsewhere").append("name", "Lluna").append("status", "ACTIVE")
                .append("version", 0), "dogs");
        var starts = local("2026-10-08T18:50");
        mongo.insert(new Document("_id", "s08-b-elsewhere").append("clubId", OTHER).append("classSessionId", "s08-elsewhere-thu").append("dogId", "s08-d-elsewhere")
                .append("memberId", "s08-m-elsewhere").append("state", "ACTIVE").append("origin", "APP").append("bookedAt", Date.from(NOW))
                .append("classStartsAt", Date.from(starts)).append("classEndsAt", Date.from(starts.plus(Duration.ofHours(1)))).append("bookingWeekKey", "2026-10-04")
                .append("version", 0L), "bookings");
        return "s08-b-elsewhere";
    }
    List<Document> remindersOf(String clubId) { return mongo.find(Query.query(Criteria.where("clubId").is(clubId).and("type").is("ReminderDue")), Document.class, "domain_events"); }

    @Test void T_15_17_aTrainingBookingIsRemindedWithItsRingOnlyWithFreeTraining() {
        // Pere (his account in English, 2 h) and a free training of Nit on Central, Thursday 08-10 08:00–08:30, booked on Tuesday.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-pere")), new Update().set("locale", "en"), "accounts");
        lead("s08-m-pere", 120);
        training("s08-t-nit", "s08-d-nit", "s08-m-pere", local("2026-10-08T08:00"), NOW);
        // FREE_TRAINING off: nothing planned at 06:00.
        modules(Arrays.stream(Module.values()).filter(m -> m != Module.FREE_TRAINING).toArray(Module[]::new));
        assertThat(tick("2026-10-08T06:00").orElseThrow().items()).isEmpty();
        assertThat(reminders()).isEmpty();
        // With the module: the 06:01 tick reminds of it (due since 06:00, still before 08:00).
        modules(Module.values());
        var run = tick("2026-10-08T06:01").orElseThrow();
        assertThat(run.items()).extracting(JobRun.Item::entityType, JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple("TrainingBooking", "s08-t-nit", "REMIND"));
        assertThat(counters(run)).isEqualTo(Map.of("trainingReminders", 1L));
        assertThat(reminders().getFirst().get("payload", Document.class)).containsEntry("trainingBookingId", "s08-t-nit").containsEntry("memberId", "s08-m-pere")
                .containsEntry("dogId", "s08-d-nit").doesNotContainKey("bookingId");
        // Its object is the training booking (ruling E82): aggregate `TrainingBooking`, not `Booking`.
        assertThat(reminders().getFirst().getString("aggregateType")).isEqualTo("TrainingBooking");
        assertThat(reminders().getFirst().getString("aggregateId")).isEqualTo("s08-t-nit");
        assertThat(mongo.findById("s08-t-nit", Document.class, "training_bookings").get("reminderSentAt")).isNotNull();
        dispatch();
        var rows = NotificationRows.rows(mongo, CLUB, "N-13");
        assertThat(rows).isNotEmpty().allSatisfy(row -> {
            assertThat(row.getString("memberId")).isEqualTo("s08-m-pere");
            assertThat(row.getString("locale")).isEqualTo("en");
            assertThat(row.getString("title")).isEqualTo("Training reminder");
            assertThat(row.get("variables", Document.class).getString("ring_name")).isEqualTo("Central");
        });
        assertThat(rows.getFirst().getString("body")).contains("Central").contains("Nit");
        assertThat(tick("2026-10-08T06:02").orElseThrow().items()).isEmpty();
    }

    @Test void T_15_17_aFamilyMembersTrainingRemindsTheDogsOwnerWithTheOwnersLead() {
        // Joan (family group of Laura) books Laura's Duna; Laura (the owner) has 1 h, Joan none: Laura is reminded.
        lead("s08-m-laura", 60);
        training("s08-t-duna", "s08-d-duna", "s08-m-joan", local("2026-10-08T08:00"), NOW);
        assertThat(tick("2026-10-08T06:59").orElseThrow().items()).isEmpty();
        assertThat(tick("2026-10-08T07:00").orElseThrow().items()).extracting(JobRun.Item::entityId).containsExactly("s08-t-duna");
        assertThat(reminders().getFirst().get("payload", Document.class)).containsEntry("memberId", "s08-m-laura");
        dispatch();
        assertThat(NotificationRows.rows(mongo, CLUB, "N-13")).extracting(r -> r.getString("memberId")).containsOnly("s08-m-laura");
    }

    @Test void T_15_18_aPreferenceChangedAt1000AppliesAtThe1000TickBothWays() {
        session("r1030", "2026-10-09T10:30", 3, List.of());
        // 24 h → 1 h: Joan books Friday's 10:30 class on Thursday 11:00, after its 24 h moment (Thursday 10:30): nothing with 24 h.
        lead("s08-m-joan", 1440);
        String toby = stored("s08-b-toby", "r1030", "s08-d-toby", "s08-m-joan", "ACTIVE", local("2026-10-08T11:00"));
        assertThat(tick("2026-10-09T09:59").orElseThrow().items()).isEmpty();
        lead("s08-m-joan", 60);   // Friday 10:00, in 12
        assertThat(tick("2026-10-09T10:00").orElseThrow().items()).extracting(JobRun.Item::entityId).containsExactly(toby);
        // 1 h → 24 h: Laura's Thursday 18:50 booking (made Tuesday) with 1 h; at Thursday 10:00 she switches to 24 h.
        lead("s08-m-laura", 60);
        String duna = stored("s08-b-duna", "thu", "s08-d-duna", "s08-m-laura", "ACTIVE", NOW);
        assertThat(tick("2026-10-08T09:59").orElseThrow().items()).isEmpty();
        lead("s08-m-laura", 1440);
        assertThat(tick("2026-10-08T10:00").orElseThrow().items()).extracting(JobRun.Item::entityId).containsExactly(duna);
        assertThat(remindedBookings()).containsExactlyInAnyOrder(toby, duna);
    }

    @Test void T_15_18_theDstDayIsInstantArithmeticInMadridAndInBuenosAires() {
        // Sunday 25-10-2026, the October fold in Madrid (03:00 CEST → 02:00 CET): the 09:00 CET class is 08:00Z; 2 h → 06:00Z exactly.
        session("dst", "2026-10-25T09:00", 3, List.of());
        assertThat(session("dst").getDate("startsAt").toInstant()).isEqualTo(Instant.parse("2026-10-25T08:00:00Z"));
        lead("s08-m-laura", 120);
        String duna = stored("s08-b-dst", "dst", "s08-d-duna", "s08-m-laura", "ACTIVE", NOW);
        clock.setInstant(Instant.parse("2026-10-25T05:59:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).isEmpty();
        clock.setInstant(Instant.parse("2026-10-25T06:00:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).extracting(JobRun.Item::entityId).containsExactly(duna);
        // 24 h across the fold: the day before at 08:00Z (10:00 CEST), not «09:00 local».
        lead("s08-m-joan", 1440);
        String toby = stored("s08-b-dst2", "dst", "s08-d-toby", "s08-m-joan", "ACTIVE", NOW);
        clock.setInstant(Instant.parse("2026-10-24T07:59:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).isEmpty();
        clock.setInstant(Instant.parse("2026-10-24T08:00:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).extracting(JobRun.Item::entityId).containsExactly(toby);
        // The same club in Buenos Aires (05:00 local for the same instant): the same 06:00Z.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("timeZone", "America/Argentina/Buenos_Aires"), "clubs");
        configs.invalidate(CLUB);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        lead("s08-m-pere", 120);
        String nit = stored("s08-b-dst3", "dst", "s08-d-nit", "s08-m-pere", "ACTIVE", NOW);
        clock.setInstant(Instant.parse("2026-10-25T05:59:00Z"));
        assertThat(runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow().items()).isEmpty();
        clock.setInstant(Instant.parse("2026-10-25T06:00:00Z"));
        var run = runner.scheduled(CLUB, true, job, clock.instant()).orElseThrow();
        assertThat(run.timeZone()).isEqualTo("America/Argentina/Buenos_Aires");
        assertThat(run.items()).extracting(JobRun.Item::entityId).containsExactly(nit);
    }

    @Test void T_15_17_aReminderPlannedButCancelledBeforeItsItemIsNotSent() {
        lead("s08-m-laura", 120);
        String duna = stored("s08-b-race", "thu", "s08-d-duna", "s08-m-laura", "ACTIVE", NOW);
        clock.setInstant(local("2026-10-08T16:50"));
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            var context = new JobContext(CLUB, MADRID, clock.instant(), LocalDate.of(2026, 10, 8), false, configs.get(CLUB), null, "run-race");
            var item = new JobItem("Booking", duna, "REMIND", Map.of("bookingId", duna));
            // The member cancels between the plan and the item: the item is out of scope and writes nothing.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(duna)), new Update().set("state", "CANCELLED"), "bookings");
            assertThat(tx.execute(status -> job.apply(context, item)).action()).isEqualTo("NOT_IN_SCOPE");
            // The lead removed («Mai») between the plan and the item: idem.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(duna)), new Update().set("state", "ACTIVE"), "bookings");
            lead("s08-m-laura", null);
            assertThat(tx.execute(status -> job.apply(context, item)).action()).isEqualTo("NOT_IN_SCOPE");
            // Already marked by another run: idem.
            lead("s08-m-laura", 120);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(duna)), new Update().set("reminderSentAt", new Date()), "bookings");
            assertThat(tx.execute(status -> job.apply(context, item)).action()).isEqualTo("NOT_IN_SCOPE");
        }
        assertThat(reminders()).isEmpty();
    }

    void training(String id, String dogId, String memberId, Instant startsAt, Instant createdAt) {
        mongo.insert(new Document("_id", id).append("clubId", CLUB).append("memberId", memberId).append("dogId", dogId).append("ringId", "s08-ring")
                .append("startsAt", Date.from(startsAt)).append("endsAt", Date.from(startsAt.plus(Duration.ofMinutes(30)))).append("slotId", "slot-" + id)
                .append("seatIndex", 0).append("weekStart", Date.from(local("2026-10-04T20:00"))).append("state", "ACTIVE").append("origin", "APP")
                .append("createdByAccountId", "s08-pere").append("createdAt", Date.from(createdAt)).append("version", 0L), "training_bookings");
    }
}
