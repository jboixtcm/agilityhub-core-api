package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.PaymentTimeoutsJob;
import com.agilityhub.core.clubs.bookings.application.WaitlistFifoJob;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Duration;
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

/** S15 R-15-16 P6 `waitlist-fifo` (T-15-24, T-08-34) and R-15-17 P7 `payment-timeouts` (T-15-25, T-08-35) on the fictional S08 club. */
class BookingJobsIT extends BookingFixtures {
    @Autowired JobRunner runner;
    @Autowired WaitlistFifoJob fifo;
    @Autowired PaymentTimeoutsJob timeouts;

    @BeforeEach void jobs() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(new Query(), "job_locks");
    }
    private Map<String, Long> counters(JobRun run) {
        var map = new TreeMap<String, Long>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
    private List<Document> notifications(String code) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), Document.class, "notifications");
    }

    @Test void T_15_24_T_08_34_aFifoOfferExpiresAtConfirmByIntoTheNextEntryOnce() throws Exception {
        parameter("waitlist.mode", "FIFO");
        String laura = book(as("laura"), "last", "s08-d-duna").path("id").asText();
        String pere = join(as("pere"), "last", "s08-d-nit", 201).path("id").asText();
        String joan = join(as("joan"), "last", "s08-d-toby", 201).path("id").asText();
        cancel(as("laura"), laura, 200);
        dispatch();
        assertThat(entry(pere).getString("state")).isEqualTo("NOTIFIED");
        Instant confirmBy = entry(pere).getDate("confirmBy").toInstant();
        assertThat(confirmBy).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        // One minute before confirmBy nothing is due.
        clock.setInstant(confirmBy.minusSeconds(60));
        var early = runner.scheduled(CLUB, true, fifo, clock.instant()).orElseThrow();
        assertThat(early.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(early.items()).isEmpty();
        // The tick of confirmBy expires Pere and emits WaitlistExpired{entryId, classId}.
        clock.setInstant(confirmBy);
        var dry = runner.manual(CLUB, JobName.WAITLIST_FIFO, true, "s08-admin");
        assertThat(dry.items()).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("WOULD_EXPIRE"); assertThat(item.entityId()).isEqualTo(pere);
            assertThat(item.detail()).contains(new JobRun.Entry("position", 1));
        });
        assertThat(entry(pere).getString("state")).isEqualTo("NOTIFIED");
        var run = runner.scheduled(CLUB, true, fifo, confirmBy).orElseThrow();
        assertThat(run.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple(pere, "EXPIRE"));
        assertThat(counters(run)).containsEntry("expired", 1L);
        assertThat(entry(pere).getString("state")).isEqualTo("EXPIRED");
        assertThat(eventsOf("WaitlistExpired")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("entryId", pere).containsEntry("classId", "s08-last"));
        System.out.println("E5-T05 waitlist-fifo JobRun dry  " + mongo.findById(dry.id(), Document.class, "job_runs").toJson());
        System.out.println("E5-T05 waitlist-fifo JobRun real " + mongo.findById(run.id(), Document.class, "job_runs").toJson());
        // S08's consumer offers the seat to Joan (N-15); a second delivery of the same event offers nothing more (T-08-34).
        dispatch();
        assertThat(entry(joan).getString("state")).isEqualTo("NOTIFIED");
        long offers = events("WaitlistNotified");
        publish(new SchedulerEvent(SchedulerEvent.Kind.WaitlistExpired, CLUB, pere, clock.instant(), Map.of("entryId", pere, "classId", "s08-last"),
                null, null, DomainEvent.Origin.SYSTEM));
        dispatch();
        assertThat(events("WaitlistNotified")).isEqualTo(offers);
        assertThat(mongo.count(Query.query(Criteria.where("classSessionId").is("s08-last").and("state").is("NOTIFIED")), "waitlist_entries")).isEqualTo(1);
        // A second run of the same minute finds nothing (idempotent by state).
        assertThat(runner.manual(CLUB, JobName.WAITLIST_FIFO, false, "s08-admin").items()).isEmpty();
        // ALL_AT_ONCE (the Cànic): SKIPPED{MODULE_OFF} at most once per hour, 404 on demand.
        parameter("waitlist.mode", "ALL_AT_ONCE");
        var skipped = runner.scheduled(CLUB, true, fifo, clock.instant().plusSeconds(60)).orElseThrow();
        assertThat(skipped.skipReason()).isEqualTo(SkipReason.MODULE_OFF);
        assertThat(runner.scheduled(CLUB, true, fifo, clock.instant().plusSeconds(120))).isEmpty();
        assertThat(runner.scheduled(CLUB, true, fifo, clock.instant().plus(Duration.ofMinutes(62)))).isPresent();
        assertThatThrownBy(() -> runner.manual(CLUB, JobName.WAITLIST_FIFO, false, "s08-admin"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MODULE_DISABLED));
    }

    /** An open FIFO offer of `class` whose `confirmBy` has just passed (the offer path itself is T-15-24's). */
    private String dueOffer(String classId, org.springframework.test.web.servlet.request.RequestPostProcessor who, String dogId) throws Exception {
        String id = join(who, classId, dogId, 201).path("id").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("state", "NOTIFIED")
                .set("notifiedAt", NOW.minus(Duration.ofMinutes(31))).set("confirmBy", NOW.minus(Duration.ofMinutes(1))), "waitlist_entries");
        return id;
    }

    @Test void R_15_12b_aFifoExpiryThatLeavesTheClassBelowTheMinimumAlertsStaffInTheSameTransaction() throws Exception {
        parameter("waitlist.mode", "FIFO");
        // «last» (capacity 1) keeps Laura's single dog: below classes.minDogs = 2, with no low alert standing (a booking never alerts).
        book(as("laura"), "last", "s08-d-duna");
        assertThat(session("last").get("risk", Document.class).get("lowAlertSentAt")).isNull();
        String pere = dueOffer("last", as("pere"), "s08-d-nit");
        String joan = dueOffer("last", as("joan"), "s08-d-toby");
        dispatch(); mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "notifications");
        long before = events("ClassBelowMinimum");
        var run = runner.scheduled(CLUB, true, fifo, NOW).orElseThrow();
        assertThat(run.items()).extracting(JobRun.Item::entityId).containsExactlyInAnyOrder(pere, joan);
        // Emitted by P6's own item transaction (already in the outbox, before any consumer ran), once: the second expiry finds the mark.
        assertThat(events("ClassBelowMinimum")).isEqualTo(before + 1);
        assertThat(eventsOf("ClassBelowMinimum")).anySatisfy(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("classId", "s08-last").containsEntry("countedDogs", 1).containsEntry("minDogs", 2));
        assertThat(session("last").get("risk", Document.class).get("lowAlertSentAt")).isNotNull();
        assertThat(session("last").getString("state")).isEqualTo("ACTIVE");
        dispatch();
        assertThat(notifications("N-54")).extracting(n -> n.getString("accountId") + ":" + n.getString("channel"))
                .containsExactlyInAnyOrder("s08-admin:APP", "s08-admin:EMAIL", "s08-inst:APP", "s08-inst:EMAIL");
        assertThat(eventsOf("ClassAtRisk")).isEmpty();
        // A class that has already started never alerts, even with the mark cleared.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-last")), new Update().unset("risk.lowAlertSentAt"), "class_sessions");
        String crowd = dueOffer("last", as("c0"), "s08-d-c0");
        Instant started = session("last").getDate("startsAt").toInstant().plusSeconds(60);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(crowd)), new Update().set("confirmBy", started.minusSeconds(30)), "waitlist_entries");
        clock.setInstant(started);
        assertThat(runner.manual(CLUB, JobName.WAITLIST_FIFO, false, "s08-admin").items()).extracting(JobRun.Item::entityId).containsExactly(crowd);
        assertThat(events("ClassBelowMinimum")).isEqualTo(before + 1);
    }

    private String pending(String id, String classId, String dogId, String memberId, Instant bookedAt) {
        var session = session(classId.substring("s08-".length()));
        Instant starts = session.getDate("startsAt").toInstant();
        mongo.insert(new Booking(id, CLUB, classId, dogId, memberId, BookingState.PAYMENT_PENDING, BookingOrigin.APP, bookedAt,
                new Booking.Actor("s08-" + memberId.substring("s08-m-".length()), null, "Example"), starts, starts.plusSeconds(3600), "2026-10-04", null, null, null,
                null, null, null, null, null, null, null, null, null, null, 1L, bookedAt, null, bookedAt, null));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(classId)), new Update().inc("counters.booked", 1), "class_sessions");
        return id;
    }

    @Test void T_15_25_T_08_35_aStalePaymentPendingBookingIsCancelledOnceAndItsSeatReleased() throws Exception {
        String stale = pending("s08-pay-stale", "s08-wed", "s08-d-duna", "s08-m-laura", NOW.minus(Duration.ofMinutes(31)));
        String fresh = pending("s08-pay-fresh", "s08-wed", "s08-d-nit", "s08-m-pere", NOW.minus(Duration.ofMinutes(29)));
        var dry = runner.manual(CLUB, JobName.PAYMENT_TIMEOUTS, true, "s08-admin");
        assertThat(dry.items()).singleElement().satisfies(item -> {
            assertThat(item.entityId()).isEqualTo(stale); assertThat(item.action()).isEqualTo("WOULD_CANCEL");
            assertThat(item.detail()).contains(new JobRun.Entry("minutesPending", 31L));
        });
        assertThat(booking(stale).getString("state")).isEqualTo("PAYMENT_PENDING");
        var run = runner.scheduled(CLUB, true, timeouts, NOW).orElseThrow();
        assertThat(run.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple(stale, "CANCEL"));
        assertThat(run.items().getFirst().detail()).isEqualTo(dry.items().getFirst().detail());
        assertThat(booking(stale).getString("state")).isEqualTo("CANCELLED");
        assertThat(booking(stale).getString("cancelReason")).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(booking(fresh).getString("state")).isEqualTo("PAYMENT_PENDING");
        assertThat(eventsOf("SeatReleased")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("classId", "s08-wed"));
        assertThat(session("wed").get("counters", Document.class).getInteger("booked")).isEqualTo(1);
        // A second run of the same minute cancels nothing more: one cancellation (T-08-35).
        assertThat(runner.manual(CLUB, JobName.PAYMENT_TIMEOUTS, false, "s08-admin").items()).isEmpty();
        assertThat(events("BookingCancelled")).isEqualTo(1);
        dispatch();
        assertThat(notifications("N-40")).extracting(n -> n.getString("accountId")).containsOnly("s08-laura");
        // The next tick is thirty minutes after the fresh booking: it goes too.
        clock.setInstant(NOW.plus(Duration.ofMinutes(1)));
        runner.scheduled(CLUB, true, timeouts, clock.instant());
        assertThat(booking(fresh).getString("state")).isEqualTo("CANCELLED");
        assertThat(events("BookingCancelled")).isEqualTo(2);
        System.out.println("E5-T05 payment-timeouts JobRun dry  " + mongo.findById(dry.id(), Document.class, "job_runs").toJson());
        System.out.println("E5-T05 payment-timeouts JobRun real " + mongo.findById(run.id(), Document.class, "job_runs").toJson());
        // SINGLE_CLASS off (the Cànic): SKIPPED{MODULE_OFF} on the calendar and 404 on demand.
        modules(Module.WAITLIST, Module.PACKS, Module.FREE_TRAINING);
        var skipped = runner.scheduled(CLUB, true, timeouts, clock.instant().plus(Duration.ofMinutes(5))).orElseThrow();
        assertThat(skipped.status()).isEqualTo(JobStatus.SKIPPED);
        assertThat(skipped.skipReason()).isEqualTo(SkipReason.MODULE_OFF);
        assertThatThrownBy(() -> runner.manual(CLUB, JobName.PAYMENT_TIMEOUTS, false, "s08-admin"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MODULE_DISABLED));
    }
}
