package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.census.application.ports.TrainingCancellationPort;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** T-15-21's defensive leave sweep through P5's real per-item transaction. */
class E8LeaveProcessesIT extends BookingFixtures {
    @Autowired JobRunner jobs;
    @Autowired com.agilityhub.core.clubs.census.application.LifecycleBookings lifecycle;
    @MockitoSpyBean TrainingCancellationPort training;
    String kept, cancelled;
    @BeforeEach void leaveFixture() throws Exception {
        for (String collection : List.of("job_runs", "job_locks", "job_action_marks", "activity_registrations", "activities")) {
            mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), collection);
        }
        kept = book(as("laura"), "last", "s08-d-duna").path("id").asText();
        cancelled = book(as("laura"), "fri", "s08-d-rock").path("id").asText();
        var at = Date.from(local("2026-10-10T17:00"));
        mongo.insert(new Document("_id", "p5-training").append("clubId", CLUB).append("memberId", "s08-m-laura").append("dogId", "s08-d-duna")
                .append("ringId", "s08-ring").append("startsAt", at).append("endsAt", Date.from(local("2026-10-10T18:00")))
                .append("slotId", "p5-slot").append("seatIndex", 0).append("weekStart", at).append("state", "ACTIVE").append("origin", "APP")
                .append("version", 0L).append("createdAt", Date.from(NOW)), "training_bookings");
        mongo.insert(new Document("_id", "p5-wait").append("clubId", CLUB).append("memberId", "s08-m-laura").append("dogId", "s08-d-rock")
                .append("classSessionId", "s08-sat").append("classStartsAt", at).append("state", "ACTIVE").append("position", 1)
                .append("joinedAt", Date.from(NOW)).append("version", 0L), "waitlist_entries");
        // Legacy fixture with remaining reservations: P5 must enforce the sweep even if approval did not do it.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("leaveDate", "2026-10-08"), "members");
    }
    @Test void T_15_21_T_13_28_p5CancelsLaterReservationsKeepsTheLastEveningAndSendsNoCancellationNotices() {
        clock.setInstant(local("2026-10-08T23:59"));
        assertThat(jobs.manual(CLUB, JobName.EXPIRATIONS, false, "s08-admin").items()).noneMatch(i -> i.action().equals("LEAVE"));
        assertThat(booking(kept).getString("state")).isEqualTo("ACTIVE");
        clock.setInstant(local("2026-10-09T00:10"));
        var dry = jobs.manual(CLUB, JobName.EXPIRATIONS, true, "s08-admin");
        // The Friday class, the Saturday waitlist entry and the Saturday training; the description names what P5 really found.
        assertThat(dry.items()).filteredOn(i -> i.action().equals("WOULD_LEAVE")).singleElement().satisfies(i ->
                assertThat(i.detail()).as(this::plannedCancellations).contains(new com.agilityhub.core.platform.persistence.jobs.JobRun.Entry("futureBookings", 3)));
        var run = jobs.manual(CLUB, JobName.EXPIRATIONS, false, "s08-admin");
        assertThat(run.status()).as(run.errors().toString()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(booking(cancelled)).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "LEAVE");
        assertThat(booking(kept).getString("state")).isEqualTo("ACTIVE");
        assertThat(mongo.findById("p5-wait", Document.class, "waitlist_entries")).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "LEAVE");
        assertThat(mongo.findById("p5-training", Document.class, "training_bookings")).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "MEMBER_LEFT");
        dispatch();
        assertThat(mongo.findById("s08-m-laura", Document.class, "members")).containsEntry("status", "LEFT");
        assertThat(mongo.findById("s08-laura", Document.class, "memberships")).containsEntry("status", "SUSPENDED");
        assertThat(mongo.findById("s08-d-duna", Document.class, "dogs")).containsEntry("status", "INACTIVE");
        assertThat(count("notifications", Criteria.where("code").in("N-05", "N-07"))).isZero();
        assertThat(jobs.manual(CLUB, JobName.EXPIRATIONS, false, "s08-admin").items()).noneMatch(i -> i.action().equals("LEAVE"));
        assertThat(events("MemberStatusChanged")).isEqualTo(1);
    }
    String plannedCancellations() {
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            return "planned " + lifecycle.inside("s08-m-laura", LocalDate.parse("2026-10-09"), null, false, true);
        }
    }
    @Test void T_15_21_anS09FailureRollsBackTheWholeP5ItemAndReportsPartial() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new ApiException(ErrorCode.INVALID_STATE);
        }).when(training).inside(eq("s08-m-laura"), any(), any(), eq(true), eq(true));
        clock.setInstant(local("2026-10-09T00:10"));
        var run = jobs.manual(CLUB, JobName.EXPIRATIONS, false, "s08-admin");
        assertThat(run.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(run.errors()).singleElement().satisfies(e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));
        assertThat(booking(cancelled).getString("state")).isEqualTo("ACTIVE");
        assertThat(mongo.findById("p5-wait", Document.class, "waitlist_entries").getString("state")).isEqualTo("ACTIVE");
        assertThat(mongo.findById("p5-training", Document.class, "training_bookings").getString("state")).isEqualTo("ACTIVE");
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").getString("status")).isEqualTo("ACTIVE");
        assertThat(events("MemberStatusChanged")).isZero(); assertThat(events("BookingCancelled")).isZero();
        assertThat(events("TrainingCancelled")).isZero();
    }
}
