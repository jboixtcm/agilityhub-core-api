package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.dashboard.application.ports.BookingActivity;
import com.agilityhub.core.clubs.scheduling.application.ClassCancellationUseCase;
import com.agilityhub.core.clubs.scheduling.domain.ClassCancellationReason;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** S08 WP-08-B over real Mongo: holds, confirmation, swap, cancellation, payment, reads, roles, tenant and origin. */
class BookingsIT extends BookingFixtures {
    @Autowired BookingCancellationService cancellations; @Autowired ClassCancellationUseCase classCancellations; @Autowired BookingActivity activity;
    @Autowired com.agilityhub.core.payments.application.FakeCheckoutGateway gateway; @Autowired com.agilityhub.core.payments.application.UpfrontPayments upfrontPayments;

    @Test void T_08_14_holdCreatesRefreshesAndRejectsWithTheRightDetails() throws Exception {
        var held = hold(as("laura"), "wed", "s08-d-duna", 201);
        assertThat(held.path("serverNow").asText()).isEqualTo(NOW.toString()); assertThat(held.path("holdSeconds").asInt()).isEqualTo(30);
        assertThat(held.path("expiresAt").asText()).isEqualTo(NOW.plusSeconds(30).toString());
        assertThat(held.at("/classSession/startsAtLocal").asText()).isEqualTo("2026-10-07T18:50"); assertThat(held.at("/classSession/ringName").asText()).isEqualTo("Central");
        assertThat(held.at("/dog/name").asText()).isEqualTo("Duna"); assertThat(held.at("/limit/reached").asBoolean()).isFalse();
        assertThat(held.at("/limit/unit").asText()).isEqualTo("DOG"); assertThat(held.at("/limit/week").asText()).isEqualTo("CURRENT"); assertThat(held.at("/limit/max").asInt()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(10));
        var again = hold(as("laura"), "wed", "s08-d-duna", 201);
        assertThat(again.path("id").asText()).isEqualTo(held.path("id").asText());
        assertThat(again.path("expiresAt").asText()).isEqualTo(NOW.plusSeconds(40).toString());
        assertThat(count("seat_holds", Criteria.where("classSessionId").is("s08-wed"))).isEqualTo(1); assertThat(events("SeatHeld")).isEqualTo(2);
        assertThat(eventsOf("SeatHeld").getFirst().get("payload", Document.class)).containsKeys("classId", "memberId", "dogId", "expiresAt");
        // The last seat held by another dog → CLASS_FULL{heldOnly: true}; once booked → heldOnly false.
        var pere = hold(as("pere"), "last", "s08-d-nit", 201);
        assertThat(hold(as("laura"), "last", "s08-d-duna", 409).at("/details/heldOnly").asBoolean()).isTrue();
        confirm(as("pere"), pere.path("id").asText(), null, 201);
        assertThat(code(hold(as("laura"), "last", "s08-d-duna", 409))).isEqualTo("CLASS_FULL");
        assertThat(hold(as("laura"), "last", "s08-d-duna", 409).at("/details/heldOnly").asBoolean()).isFalse();
        var later = hold(as("laura"), "later", "s08-d-duna", 422);
        assertThat(code(later)).isEqualTo("NOT_YET_OPEN"); assertThat(later.at("/details/opensAt").asText()).isEqualTo("2026-10-11T18:00:00Z");
        // W1 allows 1: a second W1 class returns a hold with limit.reached and the first one as swappable.
        book(as("laura"), "mon", "s08-d-duna");
        var swap = hold(as("laura"), "mon2", "s08-d-duna", 201);
        assertThat(swap.at("/limit/reached").asBoolean()).isTrue(); assertThat(swap.at("/limit/week").asText()).isEqualTo("NEXT");
        assertThat(swap.at("/limit/swappable/0/startsAtLocal").asText()).isEqualTo("2026-10-12T18:50");
        // W0 with one class done and one inside the threshold → informative BOOKING_LIMIT_REACHED, no hold.
        book(as("laura"), "wed", "s08-d-duna"); book(as("laura"), "thu", "s08-d-duna");
        assertThat(code(hold(as("laura"), "wed", "s08-d-duna", 409))).isEqualTo("ALREADY_BOOKED");
        assertThat(code(hold(as("laura"), "levelD", "s08-d-duna", 422))).isEqualTo("LEVEL_NOT_ALLOWED");
        assertThat(code(hold(as("pere"), "sat", "s08-d-duna", 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        clock.setInstant(local("2026-10-08T16:00"));
        var limit = hold(as("laura"), "sat", "s08-d-duna", 409);
        assertThat(code(limit)).isEqualTo("BOOKING_LIMIT_REACHED");
        assertThat(limit.at("/details/swappable")).isEmpty(); assertThat(limit.at("/details/limit").asInt()).isEqualTo(2); assertThat(limit.at("/details/current").asInt()).isEqualTo(2);
        assertThat(limit.at("/details/notSelectable")).extracting(n -> n.path("reason").asText()).containsExactly("DONE", "LATE_WINDOW");
        assertThat(limit.at("/details/nextBookableAt").asText()).isEqualTo("2026-10-11T18:00:00Z");
        assertThat(count("seat_holds", Criteria.where("classSessionId").is("s08-sat"))).isZero();
        // DELETE → SeatHoldReleased; a second DELETE (already gone) is still 204.
        call(DELETE, "/seat-holds/" + swap.path("id").asText(), null, as("laura"), 204);
        call(DELETE, "/seat-holds/" + swap.path("id").asText(), null, as("laura"), 204);
        assertThat(count("seat_holds", Criteria.where("_id").is(swap.path("id").asText()))).isZero();
        assertThat(events("SeatHoldReleased")).isEqualTo(5); // 4 confirmations + 1 release
    }

    @Test void T_08_15_T_08_11_confirmationBooksConsumesThePackAndPublishesCalendarLinks() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 6, LocalDate.parse("2026-11-12"));
        var held = hold(as("laura"), "wed", "s08-d-duna", 201);
        assertThat(held.at("/pack/available").asInt()).isEqualTo(4);
        var booking = confirm(as("laura"), held.path("id").asText(), null, 201);
        assertThat(booking.path("state").asText()).isEqualTo("ACTIVE"); assertThat(booking.path("origin").asText()).isEqualTo("APP");
        assertThat(booking.path("memberId").asText()).isEqualTo("s08-m-laura"); assertThat(booking.at("/bookedBy/displayName").asText()).isEqualTo("Laura");
        assertThat(booking.at("/pack/available").asInt()).isEqualTo(3);
        assertThat(booking.at("/classSession/instructorName").isNull()).isTrue();
        assertThat(booking.at("/classSession/instructorVisibleAt").asText()).isEqualTo(local("2026-10-06T18:50").toString());
        assertThat(booking.at("/calendarLinks/google").asText()).startsWith("https://calendar.google.com/");
        String ics = booking.at("/calendarLinks/ics").asText(); assertThat(ics).contains("/api/v1/bookings/" + booking.path("id").asText() + "/calendar.ics?token=");
        var stored = booking(booking.path("id").asText());
        assertThat(stored.getString("bookingWeekKey")).isEqualTo("2026-10-04"); assertThat(stored.getString("packMovementId")).isNotNull();
        assertThat(stored.getDate("classStartsAt").toInstant()).isEqualTo(local("2026-10-07T18:50"));
        assertThat(count("seat_holds", Criteria.where("_id").is(held.path("id").asText()))).isZero();
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").getString("lastDogForClass")).isEqualTo("s08-d-duna");
        assertThat(session("wed").get("counters", Document.class)).containsEntry("booked", 1);
        assertThat(eventsOf("BookingCreated").getFirst().get("payload", Document.class)).containsEntry("origin", "APP").containsEntry("classId", "s08-wed")
                .containsKey("packMovementId").doesNotContainKey("swapFromBookingId");
        dispatch();
        var n04 = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-04")), Document.class, "notifications");
        assertThat(n04).extracting(n -> n.getString("channel") + ":" + n.getString("accountId")).containsExactly("APP:s08-laura");
        // .ics: signed token, text/calendar, class data and the dog name only; wrong or expired token → 404.
        String path = ics.substring(ics.indexOf("/api/v1"));
        var file = mvc.perform(get(path).header("Host", HOST)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(file.getContentType()).startsWith("text/calendar");
        assertThat(file.getContentAsString()).contains("BEGIN:VCALENDAR", "DTSTART:20261007T165000Z", "Duna").doesNotContain("Laura", "@example.test", "600000001");
        mvc.perform(get(path.replaceAll("token=[^&]+", "token=1.forged")).header("Host", HOST)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Host", OTHER_HOST)).andExpect(status().isNotFound());
        // Expired hold, and a hold of another account → SEAT_HOLD_EXPIRED.
        var expiring = hold(as("laura"), "thu", "s08-d-duna", 201); clock.advance(Duration.ofSeconds(31));
        assertThat(code(confirm(as("laura"), expiring.path("id").asText(), null, 409))).isEqualTo("SEAT_HOLD_EXPIRED");
        var peres = hold(as("pere"), "thu", "s08-d-nit", 201);
        assertThat(code(confirm(as("laura"), peres.path("id").asText(), null, 409))).isEqualTo("SEAT_HOLD_EXPIRED");
        clock.setInstant(local("2026-10-07T19:50"));
        mvc.perform(get(path).header("Host", HOST)).andExpect(status().isNotFound());
        // R-08-17: a late cancellation keeps the session consumed; an in-time one refunds it.
        clock.setInstant(NOW);
        var fri = book(as("laura"), "fri", "s08-d-duna");
        try (var t = TenantContext.open(CLUB)) { assertThat(packs.balance("s08-m-laura", "s08-d-duna").orElseThrow().available()).isEqualTo(2); }
        cancel(as("laura"), fri.path("id").asText(), 200);
        try (var t = TenantContext.open(CLUB)) { assertThat(packs.balance("s08-m-laura", "s08-d-duna").orElseThrow().available()).isEqualTo(3); }
        clock.setInstant(local("2026-10-07T17:00"));
        cancel(as("laura"), booking.path("id").asText(), 200);
        try (var t = TenantContext.open(CLUB)) { assertThat(packs.balance("s08-m-laura", "s08-d-duna").orElseThrow().available()).isEqualTo(3); }
        assertThat(booking(booking.path("id").asText()).getString("packRefundMovementId")).isNull();
        // An expired pack rejects the booking (PACK_EMPTY) and a refund into it is lost.
        clock.setInstant(NOW); openPack("s08-m-joan", "s08-d-toby", 10, 5, LocalDate.parse("2026-10-07"));
        assertThat(code(hold(as("joan"), "thu", "s08-d-toby", 422))).isEqualTo("PACK_EMPTY");
        try (var t = TenantContext.open(CLUB)) { assertThat(packs.refund("s08-m-joan", "s08-d-toby", "x", LocalDate.parse("2026-10-08"))).isNull(); }
    }

    @Test void T_08_16_theSameIdempotencyKeyReplaysTheResponseAlsoAStored409() throws Exception {
        var held = hold(as("laura"), "wed", "s08-d-duna", 201); String key = UUID.randomUUID().toString();
        var body = Map.of("seatHoldId", held.path("id").asText());
        var first = call(POST, "/bookings", body, as("laura"), 201, key);
        assertThat(call(POST, "/bookings", body, as("laura"), 201, key)).isEqualTo(first);
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-wed"))).isEqualTo(1);
        var late = hold(as("laura"), "thu", "s08-d-duna", 201); String other = UUID.randomUUID().toString();
        clock.advance(Duration.ofSeconds(31));
        var rejected = call(POST, "/bookings", Map.of("seatHoldId", late.path("id").asText()), as("laura"), 409, other);
        assertThat(code(rejected)).isEqualTo("SEAT_HOLD_EXPIRED");
        clock.advance(Duration.ofSeconds(-31)); // the hold would be valid again: the stored 409 is replayed, nothing re-executes
        assertThat(call(POST, "/bookings", Map.of("seatHoldId", late.path("id").asText()), as("laura"), 409, other)).isEqualTo(rejected);
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-thu"))).isZero();
        assertThat(code(call(POST, "/bookings", Map.of("seatHoldId", held.path("id").asText()), as("laura"), 409, other))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test void T_08_17_swapCancelsTheOldBookingAndCreatesTheNewOneAtomically() throws Exception {
        var mon = book(as("laura"), "mon", "s08-d-duna");
        mongo.insert(new com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry("s08-entry", CLUB, "s08-mon", "s08-d-nit", "s08-m-pere", "s08-pere", NOW,
                WaitlistState.ACTIVE, 1, null, null, null, null, null, local("2026-10-12T18:50"), "2026-10-11", null, NOW, "s08-pere", NOW, "s08-pere"));
        var wed = book(as("laura"), "wed", "s08-d-duna");
        var held = hold(as("laura"), "mon2", "s08-d-duna", 201);
        // Another week's booking, or nothing chosen while the limit is reached → SWAP_NOT_ALLOWED, nothing changes.
        var before = mongo.findAll(Document.class, "bookings");
        assertThat(code(confirm(as("laura"), held.path("id").asText(), wed.path("id").asText(), 422))).isEqualTo("SWAP_NOT_ALLOWED");
        assertThat(code(confirm(as("laura"), held.path("id").asText(), null, 422))).isEqualTo("SWAP_NOT_ALLOWED");
        assertThat(mongo.findAll(Document.class, "bookings")).isEqualTo(before);
        var swapped = confirm(as("laura"), held.path("id").asText(), mon.path("id").asText(), 201);
        assertThat(swapped.path("swapFromBookingId").asText()).isEqualTo(mon.path("id").asText());
        var old = booking(mon.path("id").asText());
        assertThat(old).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "SWAP").containsEntry("late", false)
                .containsEntry("swapToBookingId", swapped.path("id").asText());
        var released = eventsOf("SeatReleased").getFirst().get("payload", Document.class);
        assertThat(released).containsEntry("classId", "s08-mon").containsEntry("notifyWaitlist", true).containsEntry("freeSeats", 3);
        assertThat(session("mon").get("counters", Document.class)).containsEntry("booked", 0).containsEntry("waiting", 1);
        assertThat(eventsOf("BookingCreated").stream().filter(e -> e.getString("aggregateId").equals(swapped.path("id").asText())).findFirst().orElseThrow()
                .get("payload", Document.class)).containsEntry("swapFromBookingId", mon.path("id").asText());
        // A late (LATE_WINDOW) or done booking cannot be swapped, even while another one is still swappable: SWAP_NOT_ALLOWED, nothing changes.
        var thu = book(as("laura"), "thu", "s08-d-duna");
        clock.setInstant(local("2026-10-07T16:00")); // Wednesday 18:50 is inside the 240 min window, Thursday is not
        var lateHold = hold(as("laura"), "sat", "s08-d-duna", 201);
        assertThat(lateHold.at("/limit/swappable")).extracting(s -> s.path("startsAtLocal").asText()).containsExactly("2026-10-08T18:50");
        before = mongo.findAll(Document.class, "bookings");
        assertThat(code(confirm(as("laura"), lateHold.path("id").asText(), wed.path("id").asText(), 422))).isEqualTo("SWAP_NOT_ALLOWED");
        assertThat(mongo.findAll(Document.class, "bookings")).isEqualTo(before);
        clock.setInstant(local("2026-10-07T20:00")); // Wednesday is done
        var doneHold = hold(as("laura"), "sat", "s08-d-duna", 201);
        assertThat(doneHold.at("/limit/swappable")).extracting(s -> s.path("startsAtLocal").asText()).containsExactly("2026-10-08T18:50");
        assertThat(code(confirm(as("laura"), doneHold.path("id").asText(), wed.path("id").asText(), 422))).isEqualTo("SWAP_NOT_ALLOWED");
        assertThat(mongo.findAll(Document.class, "bookings")).isEqualTo(before);
        assertThat(booking(thu.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        // With both done or late there is nothing to swap: BOOKING_LIMIT_REACHED, no hold.
        clock.setInstant(local("2026-10-08T16:00"));
        var sat = hold(as("laura"), "sat", "s08-d-duna", 409);
        assertThat(code(sat)).isEqualTo("BOOKING_LIMIT_REACHED");
    }

    @Test void R_15_12b_aSameClassSwapKeepsTheCountAndNeverAlertsBelowMinimum() throws Exception {
        parameter("bookings.limitUnit", "MEMBER"); parameter("classes.minDogs", 2);
        var duna = book(as("laura"), "mon", "s08-d-duna"); book(as("pere"), "mon", "s08-d-nit"); // 2 of 3 seats = minDogs
        // W1 allows one class per member: Laura's Rock on the same class swaps out Duna's booking.
        var held = hold(as("laura"), "mon", "s08-d-rock", 201);
        assertThat(held.at("/limit/reached").asBoolean()).isTrue();
        var rock = confirm(as("laura"), held.path("id").asText(), duna.path("id").asText(), 201);
        assertThat(rock.path("swapFromBookingId").asText()).isEqualTo(duna.path("id").asText());
        assertThat(booking(duna.path("id").asText())).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "SWAP");
        assertThat(events("ClassBelowMinimum")).isZero();
        assertThat(session("mon").get("counters", Document.class)).containsEntry("booked", 2);
        assertThat(session("mon").get("risk", Document.class).get("lowAlertSentAt")).isNull();
    }

    @Test void R_08_23_bookingsNeverMakeTheMembersCensusFormStale() throws Exception {
        var version = mongo.findById("s08-m-laura", Document.class, "members").get("version");
        book(as("laura"), "wed", "s08-d-duna"); book(as("laura"), "thu", "s08-d-duna");
        dispatch();
        var member = mongo.findById("s08-m-laura", Document.class, "members");
        assertThat(member.getString("lastDogForClass")).isEqualTo("s08-d-duna");
        assertThat(member.get("version")).as("lastDogForClass takes no part in the member's optimistic lock").isEqualTo(version);
        // The admin saves the form loaded before the bookings: no STALE_VERSION, and the census save keeps lastDogForClass.
        call(PATCH, "/members/s08-m-laura", Map.of("version", ((Number) version).longValue(), "remarks", "Example remark"), as("admin"), 200);
        member = mongo.findById("s08-m-laura", Document.class, "members");
        assertThat(member.getString("remarks")).isEqualTo("Example remark"); assertThat(member.getString("lastDogForClass")).isEqualTo("s08-d-duna");
    }

    @Test @AuditCovers(AuditAction.BOOKING_CANCELLED_LATE)
    void T_08_18_cancellationInTimeLateAndRejectedWithSeatReleaseAndMinimumAlert() throws Exception {
        parameter("classes.minDogs", 2);
        var a = book(as("laura"), "thu", "s08-d-duna"); var b = book(as("pere"), "thu", "s08-d-nit"); var c = book(as("joan"), "thu", "s08-d-toby");
        // Exactly 4 h before (18:50 − 240 min) is in time; 4 h − 1 s is late.
        clock.setInstant(local("2026-10-08T14:50:00"));
        var inTime = cancel(as("laura"), a.path("id").asText(), 200);
        assertThat(inTime.path("state").asText()).isEqualTo("CANCELLED"); assertThat(inTime.at("/cancellation/late").asBoolean()).isFalse();
        assertThat(inTime.at("/cancellation/minutesBefore").asInt()).isEqualTo(240); assertThat(inTime.path("displayState").asText()).isEqualTo("CANCELLED");
        assertThat(eventsOf("BookingCancelled").getFirst().get("payload", Document.class)).containsEntry("by", "MEMBER").containsEntry("late", false)
                .containsEntry("reason", "MEMBER").containsEntry("origin", "APP").containsEntry("minutesBefore", 240);
        assertThat(eventsOf("SeatReleased").getFirst().get("payload", Document.class)).containsEntry("freeSeats", 1).containsEntry("notifyWaitlist", false);
        assertThat(events("ClassBelowMinimum")).isZero(); // 2 left = minDogs
        clock.setInstant(local("2026-10-08T14:50:01"));
        var late = cancel(as("pere"), b.path("id").asText(), 200);
        assertThat(late.path("state").asText()).isEqualTo("CANCELLED_LATE"); assertThat(late.at("/cancellation/late").asBoolean()).isTrue();
        assertThat(late.path("displayState").asText()).isEqualTo("CANCELLED_LATE");
        assertThat(count("audit_entries", Criteria.where("action").is("BOOKING_CANCELLED_LATE").and("entityId").is(b.path("id").asText()))).isEqualTo(1);
        assertThat(events("ClassBelowMinimum")).as("a late cancellation still counts").isZero();
        // R-15-12b: an in-time cancellation leaving 1 < 2 → one ClassBelowMinimum, guarded; recovery clears the guard.
        clock.setInstant(NOW);
        var d = book(as("laura"), "fri", "s08-d-duna"); var e = book(as("pere"), "fri", "s08-d-nit"); var f = book(as("joan"), "fri", "s08-d-toby");
        cancel(as("laura"), d.path("id").asText(), 200); assertThat(events("ClassBelowMinimum")).isZero();
        cancel(as("pere"), e.path("id").asText(), 200);
        assertThat(eventsOf("ClassBelowMinimum")).singleElement().satisfies(event -> assertThat(event.get("payload", Document.class))
                .containsEntry("classId", "s08-fri").containsEntry("countedDogs", 1).containsEntry("minDogs", 2));
        assertThat(session("fri").get("risk", Document.class).get("lowAlertSentAt")).isNotNull();
        cancel(as("joan"), f.path("id").asText(), 200); assertThat(events("ClassBelowMinimum")).isEqualTo(1);
        book(as("laura"), "fri", "s08-d-duna"); book(as("pere"), "fri", "s08-d-nit");
        assertThat(session("fri").get("risk", Document.class).get("lowAlertSentAt")).isNull();
        // After the class ended → BOOKING_NOT_CANCELLABLE; a cancelled booking too.
        clock.setInstant(local("2026-10-08T19:51"));
        assertThat(code(cancel(as("joan"), c.path("id").asText(), 422))).isEqualTo("BOOKING_NOT_CANCELLABLE");
        assertThat(code(cancel(as("laura"), a.path("id").asText(), 422))).isEqualTo("BOOKING_NOT_CANCELLABLE");
        // Started class: late with a negative minutesBefore.
        clock.setInstant(local("2026-10-08T19:05"));
        var started = cancel(as("joan"), c.path("id").asText(), 200);
        assertThat(started.at("/cancellation/minutesBefore").asInt()).isEqualTo(-15); assertThat(events("SeatReleased")).as("started: no release").isEqualTo(5);
        // ADMIN without impersonation → 403; INSTRUCTOR with bookings.instructorLastMinuteNotice = false → 403.
        clock.setInstant(NOW); var g = book(as("laura"), "sat", "s08-d-duna");
        cancel(as("admin"), g.path("id").asText(), 403);
        parameter("bookings.instructorLastMinuteNotice", false);
        assertThat(code(cancel(as("inst"), g.path("id").asText(), 403))).isEqualTo("FORBIDDEN");
        assertThat(booking(g.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
    }

    @Test void T_08_23_bookingBlockAndInactivityRejectNewBookingsButKeepExistingOnes() throws Exception {
        var kept = book(as("laura"), "wed", "s08-d-duna");
        var held = hold(as("laura"), "thu", "s08-d-duna", 201);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("bookingBlock", new Document("active", true).append("reason", "rebut impagat")), "members");
        var blocked = hold(as("laura"), "fri", "s08-d-duna", 422);
        assertThat(code(blocked)).isEqualTo("BOOKING_BLOCKED"); assertThat(blocked.at("/details/reason").asText()).isEqualTo("rebut impagat");
        assertThat(code(confirm(as("laura"), held.path("id").asText(), null, 422))).isEqualTo("BOOKING_BLOCKED");
        // The owner's block also stops a group member booking the owner's dog; the existing booking is kept, nothing audited.
        assertThat(code(hold(as("joan"), "fri", "s08-d-duna", 422))).isEqualTo("BOOKING_BLOCKED");
        assertThat(booking(kept.path("id").asText()).getString("state")).isEqualTo("ACTIVE");
        assertThat(count("audit_entries", new Criteria())).isZero();
        // The booker's block stops Laura booking Joan's Toby.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("bookingBlock", new Document("active", false)), "members");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-joan")), new Update().set("bookingBlock", new Document("active", true)), "members");
        assertThat(code(hold(as("laura"), "fri", "s08-d-toby", 422))).isEqualTo("BOOKING_BLOCKED");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-joan")), new Update().set("bookingBlock", new Document("active", false)), "members");
        try (var t = TenantContext.open(CLUB)) { inactivity.approve("s08-m-laura", LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-31")); }
        var period = hold(as("laura"), "fri", "s08-d-duna", 422);
        assertThat(code(period)).isEqualTo("INACTIVITY_PERIOD"); assertThat(period.path("details").toString()).contains("2026-10-09", "2026-10-31");
        hold(as("laura"), "thu", "s08-d-duna", 201);
        modules(java.util.Arrays.stream(Module.values()).filter(m -> m != Module.INACTIVITY).toArray(Module[]::new));
        hold(as("laura"), "fri", "s08-d-duna", 201);
        // A future leave date: classes from the end of that local day on are not bookable.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-pere")), new Update().set("leaveDate", "2026-10-08"), "members");
        hold(as("pere"), "thu", "s08-d-nit", 201);
        var leaving = hold(as("pere"), "sat", "s08-d-nit", 422);
        assertThat(code(leaving)).isEqualTo("CLASS_NOT_BOOKABLE"); assertThat(leaving.at("/details/reason").asText()).isEqualTo("LEAVING");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-pere")), new Update().set("status", "LEFT"), "members");
        assertThat(code(hold(as("pere"), "sat", "s08-d-nit", 422))).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    @Test void T_08_24_singleClassPayToBookChargeOnAttendanceAndModuleOff() throws Exception {
        payToBook();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")),
                new Update().set("paymentMethod", new Document("type", "CARD").append("card", new Document("last4", "1111"))), "members");
        var held = hold(as("laura"), "wed", "s08-d-duna", 201);
        assertThat(held.at("/payment/mode").asText()).isEqualTo("PAY_TO_BOOK"); assertThat(held.at("/payment/price/amountMinor").asLong()).isEqualTo(1200);
        var pending = confirm(as("laura"), held.path("id").asText(), null, 201);
        String bookingId = pending.path("id").asText(), sessionId = checkoutSession(bookingId);
        assertThat(pending.path("state").asText()).isEqualTo("PAYMENT_PENDING"); assertThat(pending.path("checkoutUrl").asText()).isEqualTo("https://checkout.test/" + sessionId);
        assertThat(pending.path("displayState").asText()).isEqualTo("PAYMENT_PENDING"); assertThat(events("BookingCreated")).isZero();
        assertThat(session("wed").get("counters", Document.class)).containsEntry("booked", 1);
        // One provider checkout carrying the bookingId, one SINGLE_CLASS line with the bookingId, one PENDING session expiring after bookings.paymentPendingMinutes.
        var request = gateway.request(sessionId);
        assertThat(request.metadata()).containsEntry("bookingId", bookingId); assertThat(request.clientReferenceId()).isEqualTo(bookingId);
        assertThat(request.lines()).singleElement().satisfies(l -> assertThat(l.amount()).isEqualTo(new Money(1200, "EUR")));
        assertThat(line(bookingId)).containsEntry("concept", "SINGLE_CLASS").containsEntry("status", "CHECKOUT_PENDING").containsEntry("checkoutSessionId", sessionId);
        assertThat(mongo.findById(sessionId, Document.class, "checkout_sessions")).containsEntry("bookingId", bookingId).containsEntry("status", "PENDING")
                .containsEntry("expiresAt", Date.from(NOW.plus(Duration.ofMinutes(30))));
        // The signup flows never list, charge or cancel the booking's line.
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(upfrontPayments.lines("s08-m-laura", null)).isEmpty(); assertThat(upfrontPayments.due("s08-m-laura", null, "EUR").amountMinor()).isZero();
            assertThat(upfrontPayments.reject("s08-m-laura", null)).isFalse();
        }
        assertThat(line(bookingId)).containsEntry("status", "CHECKOUT_PENDING");
        // The provider completes the checkout → UpfrontPaymentSucceeded{bookingId} → ACTIVE + BookingCreated + N-04.
        gateway.complete(sessionId); dispatch();
        assertThat(booking(bookingId).getString("state")).isEqualTo("ACTIVE"); assertThat(booking(bookingId).get("charge", Document.class).get("paidAt")).isNotNull();
        assertThat(eventsOf("UpfrontPaymentSucceeded")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("bookingId", bookingId).containsEntry("concept", "SINGLE_CLASS").containsEntry("provider", "STRIPE"));
        assertThat(events("BookingCreated")).isEqualTo(1); assertThat(count("notifications", Criteria.where("code").is("N-04"))).isEqualTo(1);
        assertThat(line(bookingId)).containsEntry("status", "PAID"); assertThat(mongo.findById(sessionId, Document.class, "checkout_sessions")).containsEntry("status", "COMPLETE");
        assertThat(mongo.findById("s08-m-laura", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class))
                .as("a booking payment never replaces the payment method").containsEntry("last4", "1111");
        // A repeated completion (webhook retry) and a late expiry change nothing.
        gateway.complete(sessionId); gateway.expire(sessionId); dispatch();
        assertThat(events("BookingCreated")).isEqualTo(1); assertThat(events("UpfrontPaymentFailed")).isZero(); assertThat(booking(bookingId).getString("state")).isEqualTo("ACTIVE");
        // The provider's checkout expires → UpfrontPaymentFailed{bookingId} → CANCELLED{PAYMENT_TIMEOUT} + SeatReleased + N-40.
        var failing = confirm(as("pere"), hold(as("pere"), "wed", "s08-d-nit", 201).path("id").asText(), null, 201);
        String failingId = failing.path("id").asText();
        gateway.expire(checkoutSession(failingId)); dispatch();
        assertThat(booking(failingId)).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "PAYMENT_TIMEOUT").containsEntry("late", false);
        assertThat(line(failingId)).containsEntry("status", "CANCELLED");
        assertThat(eventsOf("UpfrontPaymentFailed")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("bookingId", failingId));
        assertThat(eventsOf("SeatReleased")).hasSize(1); assertThat(session("wed").get("counters", Document.class)).containsEntry("booked", 1);
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-40")), Document.class, "notifications"))
                .extracting(n -> n.getString("channel")).containsExactlyInAnyOrder("APP", "EMAIL");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-plan")), new Update().set("singleClass.chargeMode", "CHARGE_ON_ATTENDANCE"), "plans");
        var attendance = book(as("laura"), "thu", "s08-d-duna");
        assertThat(attendance.path("state").asText()).isEqualTo("ACTIVE"); assertThat(attendance.at("/charge/mode").asText()).isEqualTo("CHARGE_ON_ATTENDANCE");
        assertThat(attendance.path("checkoutUrl").isMissingNode() || attendance.path("checkoutUrl").isNull()).isTrue();
        modules(java.util.Arrays.stream(Module.values()).filter(m -> m != Module.SINGLE_CLASS).toArray(Module[]::new));
        var off = book(as("laura"), "fri", "s08-d-rock");
        assertThat(off.path("charge").isMissingNode() || off.path("charge").isNull()).isTrue(); assertThat(booking(off.path("id").asText()).get("charge")).isNull();
        assertThat(count("upfront_payments", new Criteria())).as("only the two PAY_TO_BOOK lines").isEqualTo(2);
    }

    @Test void T_08_25_cancelByClubCancelsBookingsEntriesAndHoldsAllOrNothing() throws Exception {
        openPack("s08-m-laura", "s08-d-duna", 10, 0, null);
        var a = book(as("laura"), "sat", "s08-d-duna"); book(as("pere"), "sat", "s08-d-nit");
        hold(as("joan"), "sat", "s08-d-toby", 201);
        mongo.insert(new com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry("s08-entry", CLUB, "s08-sat", "s08-d-c1", "s08-m-c1", "s08-c1", NOW,
                WaitlistState.ACTIVE, 1, null, null, null, null, null, local("2026-10-10T09:00"), "2026-10-04", null, NOW, "s08-c1", NOW, "s08-c1"));
        // A failure after the port call rolls every booking back (the port joins the S06 transaction).
        try (var t = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> { cancellations.cancelByClub("s08-sat", "CLUB_MANUAL", "x", "s08-admin"); throw new IllegalStateException("abort"); }))
                    .hasMessage("abort");
        }
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-sat").and("state").is("ACTIVE"))).isEqualTo(2);
        try (var t = TenantContext.open(CLUB)) { assertThatThrownBy(() -> cancellations.cancelByClub("s08-sat", "CLUB_MANUAL", "x", "s08-admin")).isInstanceOf(Exception.class); }
        call(POST, "/class-sessions/s08-sat/cancellation", Map.of("reason", "CLUB_MANUAL", "adminText", "Fictional cancellation"), as("admin"), 200, UUID.randomUUID().toString());
        assertThat(count("bookings", Criteria.where("classSessionId").is("s08-sat").and("state").is("CANCELLED_BY_CLUB"))).isEqualTo(2);
        assertThat(booking(a.path("id").asText())).containsEntry("cancelReason", "CLUB_CLASS_CANCELLED").containsEntry("late", false);
        assertThat(booking(a.path("id").asText()).getString("packRefundMovementId")).isNotNull();
        try (var t = TenantContext.open(CLUB)) { assertThat(packs.balance("s08-m-laura", "s08-d-duna").orElseThrow().available()).isEqualTo(10); }
        assertThat(mongo.findById("s08-entry", Document.class, "waitlist_entries")).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "CLASS_CANCELLED");
        assertThat(count("seat_holds", Criteria.where("classSessionId").is("s08-sat"))).isZero();
        assertThat(session("sat").get("counters", Document.class)).containsEntry("booked", 0).containsEntry("waiting", 0);
        var payload = eventsOf("ClassCancelledByClub").getFirst().get("payload", Document.class);
        assertThat(payload.getList("affected", Document.class)).hasSize(2); assertThat(payload.getList("waitlistIds", String.class)).containsExactly("s08-entry");
        assertThat(events("BookingCancelled")).isZero();
        dispatch();
        assertThat(count("notifications", Criteria.where("code").is("N-08a").and("channel").is("APP"))).isGreaterThanOrEqualTo(3);
        assertThat(code(hold(as("joan"), "sat", "s08-d-toby", 422))).isEqualTo("CLASS_NOT_BOOKABLE");
    }

    @Test @AuditCovers({AuditAction.BOOKING_CREATED_BY_CLUB, AuditAction.BOOKING_CANCELLED_BY_CLUB, AuditAction.BOOKING_CANCELLED_LATE})
    void T_08_27_impersonatedBookingAndCancellationAreBackofficeAuditedAndNotifiedWithN36() throws Exception {
        var admin = impersonating("admin", "s08-m-laura");
        var held = hold(admin, "wed", "s08-d-duna", 201);
        assertThat(mongo.findById(held.path("id").asText(), Document.class, "seat_holds").getString("accountId")).isEqualTo("s08-admin");
        var booking = confirm(admin, held.path("id").asText(), null, 201);
        assertThat(booking.path("origin").asText()).isEqualTo("BACKOFFICE"); assertThat(booking.at("/bookedBy/viaClub").asBoolean()).isTrue();
        assertThat(booking(booking.path("id").asText()).get("bookedBy", Document.class)).containsEntry("accountId", "s08-admin").containsEntry("impersonatedMemberId", "s08-m-laura");
        var created = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("BOOKING_CREATED_BY_CLUB")), Document.class, "audit_entries");
        assertThat(created).containsEntry("actorAccountId", "s08-admin").containsEntry("impersonatedMemberId", "s08-m-laura").containsEntry("entityId", booking.path("id").asText());
        clock.setInstant(local("2026-10-07T17:00")); // inside the 4 h threshold; a fresh impersonation session
        var cancelled = cancel(impersonating("admin", "s08-m-laura"), booking.path("id").asText(), 200);
        assertThat(cancelled.at("/cancellation/byRole").asText()).isEqualTo("ADMIN"); assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED_LATE");
        assertThat(booking(booking.path("id").asText()).get("cancelledBy", Document.class)).containsEntry("accountId", "s08-admin").containsEntry("impersonatedMemberId", "s08-m-laura");
        assertThat(count("audit_entries", Criteria.where("action").is("BOOKING_CANCELLED_BY_CLUB").and("impersonatedMemberId").is("s08-m-laura"))).isEqualTo(1);
        var lateEntry = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("BOOKING_CANCELLED_LATE")), Document.class, "audit_entries");
        assertThat(lateEntry).as("S14 R-14-09: an impersonated late cancellation is audited as late too").isNotNull()
                .containsEntry("actorAccountId", "s08-admin").containsEntry("impersonatedMemberId", "s08-m-laura").containsEntry("entityId", booking.path("id").asText());
        assertThat(count("audit_entries", Criteria.where("action").is("BOOKING_CANCELLED_LATE"))).isEqualTo(1);
        assertThat(eventsOf("BookingCancelled").getFirst().get("payload", Document.class)).containsEntry("origin", "BACKOFFICE").containsEntry("by", "ADMIN");
        dispatch();
        var n36 = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-36")), Document.class, "notifications");
        assertThat(n36).extracting(n -> n.getString("channel") + ":" + n.getString("status")).containsExactlyInAnyOrder(
                "APP:SENT", "EMAIL:SENT", "SMS:QUEUED", "APP:SENT", "EMAIL:SENT", "SMS:QUEUED");
        assertThat(count("notifications", Criteria.where("code").in("N-04", "N-05"))).isZero();
        call(GET, "/bookings", null, impersonating("admin", "s08-m-laura"), 403);
        // SMS off: the intent is recorded as SKIPPED_MODULE_OFF.
        modules(java.util.Arrays.stream(Module.values()).filter(m -> m != Module.SMS).toArray(Module[]::new));
        clock.setInstant(NOW); var again = impersonating("admin", "s08-m-laura");
        confirm(again, hold(again, "thu", "s08-d-duna", 201).path("id").asText(), null, 201); dispatch();
        assertThat(count("notifications", Criteria.where("code").is("N-36").and("channel").is("SMS").and("status").is("SKIPPED_MODULE_OFF"))).isEqualTo(1);
    }

    @Test void T_08_28_theInstructorNoticeCancelsWithOriginInstructorAndN05WithoutSms() throws Exception {
        var early = book(as("laura"), "wed", "s08-d-duna"); var late = book(as("pere"), "wed", "s08-d-nit");
        var instructor = cancel(as("inst"), early.path("id").asText(), 200);
        assertThat(instructor.at("/cancellation/byRole").asText()).isEqualTo("INSTRUCTOR");
        assertThat(booking(early.path("id").asText())).containsEntry("cancelReason", "INSTRUCTOR_NOTICE").containsEntry("late", false);
        try (var t = TenantContext.open(CLUB)) {
            clock.setInstant(local("2026-10-07T18:00"));
            var service = cancellations.cancel(late.path("id").asText(), BookingActor.instructor("s08-inst", "Estela"), null);
            assertThat(service.late()).isTrue(); assertThat(service.cancelledBy().role()).isEqualTo(ActorRole.INSTRUCTOR);
        }
        assertThat(eventsOf("BookingCancelled")).extracting(e -> e.get("payload", Document.class).getString("origin")).containsOnly("INSTRUCTOR");
        dispatch();
        var n05 = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-05")), Document.class, "notifications");
        assertThat(n05).extracting(n -> n.getString("channel")).containsExactly("APP", "APP");
        assertThat(count("notifications", Criteria.where("channel").is("SMS"))).isZero();
        assertThat(n05).extracting(n -> n.get("variables", Document.class).getBoolean("late")).containsExactlyInAnyOrder(false, true);
    }

    @Test void T_08_26_rolesTenantAndOwnershipPerEndpoint() throws Exception {
        var mine = book(as("laura"), "wed", "s08-d-duna"); String id = mine.path("id").asText();
        var toby = book(as("laura"), "thu", "s08-d-toby");
        assertThat(toby.path("memberId").asText()).as("the dog's owner").isEqualTo("s08-m-joan");
        call(GET, "/bookings/" + id, null, as("laura"), 200); call(GET, "/bookings/" + id, null, as("joan"), 200); // family group
        assertThat(code(call(GET, "/bookings/" + id, null, as("pere"), 404))).isEqualTo("NOT_FOUND");
        call(GET, "/bookings/" + id, null, as("inst"), 200); call(GET, "/bookings/" + id, null, as("admin"), 200);
        call(GET, "/bookings/" + id, null, null, 401);
        assertThat(code(cancel(as("pere"), id, 404))).isEqualTo("NOT_FOUND");
        assertThat(call(GET, "/me/bookings", null, as("joan"), 200).path("items")).hasSize(2);
        assertThat(call(GET, "/me/bookings?dogId=s08-d-toby", null, as("laura"), 200).path("items")).hasSize(1);
        assertThat(call(GET, "/me/bookings", null, as("pere"), 200).path("items")).isEmpty();
        assertThat(code(call(GET, "/me/bookings?dogId=s08-d-duna", null, as("pere"), 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        assertThat(call(GET, "/me/bookings?from=2026-10-08&to=2026-10-08", null, as("laura"), 200).path("items")).hasSize(1);
        call(GET, "/me/bookings", null, as("admin"), 403); call(POST, "/seat-holds", Map.of("classSessionId", "s08-wed", "dogId", "s08-d-duna"), as("inst"), 403);
        call(POST, "/seat-holds", Map.of("classSessionId", "s08-wed", "dogId", "s08-d-duna"), null, 401);
        // FAMILY_GROUP off: the group's dogs are no longer accessible.
        modules(java.util.Arrays.stream(Module.values()).filter(m -> m != Module.FAMILY_GROUP).toArray(Module[]::new));
        assertThat(code(hold(as("laura"), "fri", "s08-d-toby", 404))).isEqualTo("DOG_NOT_ACCESSIBLE");
        call(GET, "/bookings/" + id, null, as("joan"), 404);
        // Club B never sees or touches club A.
        var otherClub = jwt().jwt(j -> j.subject("s08-laura").claim("clubId", OTHER).claim("memberId", "s08-m-laura")).authorities(() -> "ROLE_ADMIN");
        var response = mvc.perform(get("/api/v1/bookings/" + id).header("Host", OTHER_HOST).with(otherClub)).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(403, 404);
        assertThat(mvc.perform(get("/api/v1/bookings").header("Host", OTHER_HOST).with(otherClub)).andReturn().getResponse().getStatus()).isIn(200, 403);
        try (var t = TenantContext.open(OTHER)) { assertThat(activity.dogsWithBooking(OTHER, NOW, NOW.plus(Duration.ofDays(30)))).isEmpty(); }
    }

    @Test void T_08_47_theUniversalListFiltersByDeclaredFieldsOnlyForStaff() throws Exception {
        var a = book(as("laura"), "wed", "s08-d-duna"); book(as("pere"), "wed", "s08-d-nit"); var c = book(as("laura"), "mon", "s08-d-rock");
        cancel(as("laura"), a.path("id").asText(), 200);
        assertThat(call(GET, "/bookings?filter=state:eq:ACTIVE", null, as("admin"), 200).path("items")).hasSize(2);
        assertThat(call(GET, "/bookings?filter=dogId:eq:s08-d-rock", null, as("inst"), 200).path("items")).extracting(i -> i.path("id").asText()).containsExactly(c.path("id").asText());
        assertThat(call(GET, "/bookings?filter=classSessionId:eq:s08-wed", null, as("admin"), 200).path("totalItems").asInt()).isEqualTo(2);
        var week = call(GET, "/bookings?filter=bookingWeekKey:eq:2026-10-11", null, as("admin"), 200).path("items");
        assertThat(week).singleElement().satisfies(i -> { assertThat(i.path("dogName").asText()).isEqualTo("Rock"); assertThat(i.path("memberName").asText()).isEqualTo("Laura Example"); });
        assertThat(code(call(GET, "/bookings?filter=cancelMessage:eq:x", null, as("admin"), 400))).isEqualTo("INVALID_FILTER");
        call(GET, "/bookings", null, as("laura"), 403);
        assertThat(call(GET, "/class-sessions/s08-wed/bookings", null, as("inst"), 200).path("items")).hasSize(2);
        call(GET, "/class-sessions/s08-wed/bookings", null, as("laura"), 403);
        try (var t = TenantContext.open(CLUB)) {
            assertThat(activity.dogsWithBooking(CLUB, local("2026-10-07T00:00"), local("2026-10-08T00:00"))).containsExactly("s08-d-nit");
            assertThat(activity.activeBookingsByLevel(CLUB, NOW, local("2026-10-20T00:00"))).containsEntry("s08-lv-C", 1).containsEntry("s08-lv-D", 1);
        }
    }

    @Test void R_08_15_theHoldOfAClaimNeedsTheDogsNotifiedEntryStillOnOffer() throws Exception {
        book(as("pere"), "last", "s08-d-nit");
        var entry = new com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry("s08-claim", CLUB, "s08-last", "s08-d-duna", "s08-m-laura", "s08-laura", NOW,
                WaitlistState.ACTIVE, 1, null, null, null, null, null, local("2026-10-08T20:00"), "2026-10-04", null, NOW, "s08-laura", NOW, "s08-laura");
        mongo.insert(entry);
        var body = Map.of("classSessionId", "s08-last", "dogId", "s08-d-duna", "waitlistEntryId", "s08-claim");
        assertThat(code(call(POST, "/seat-holds", body, as("laura"), 422))).isEqualTo("WAITLIST_NOT_NOTIFIED");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-claim")), new Update().set("state", "NOTIFIED").set("confirmBy", Date.from(NOW.minusSeconds(1))), "waitlist_entries");
        assertThat(code(call(POST, "/seat-holds", body, as("laura"), 422))).isEqualTo("WAITLIST_OFFER_EXPIRED");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-claim")), new Update().set("confirmBy", Date.from(NOW.plusSeconds(1800))), "waitlist_entries");
        assertThat(code(call(POST, "/seat-holds", body, as("laura"), 409))).as("the seat is still booked").isEqualTo("SEAT_TAKEN");
        assertThat(code(call(POST, "/seat-holds", Map.of("classSessionId", "s08-last", "dogId", "s08-d-duna", "waitlistEntryId", "missing"), as("laura"), 404))).isEqualTo("NOT_FOUND");
        cancel(as("pere"), mongo.findOne(Query.query(Criteria.where("dogId").is("s08-d-nit")), Document.class, "bookings").getString("_id"), 200);
        var held = call(POST, "/seat-holds", body, as("laura"), 201);
        assertThat(mongo.findById(held.path("id").asText(), Document.class, "seat_holds").getString("waitlistEntryId")).isEqualTo("s08-claim");
        // Only the owner account releases a hold; another member's DELETE is a silent no-op.
        call(DELETE, "/seat-holds/" + held.path("id").asText(), null, as("pere"), 204);
        assertThat(count("seat_holds", Criteria.where("_id").is(held.path("id").asText()))).isEqualTo(1);
        assertThat(code(call(GET, "/me/bookings?from=2026-10-09&to=2026-10-08", null, as("laura"), 400))).isEqualTo("VALIDATION_ERROR");
    }

    @Test void S15_P5c_S13_systemCancellationsOfAMemberAreNeverLateAndRefundThePack() throws Exception {
        openPack("s08-m-laura", "s08-d-rock", 10, 0, null);
        var wed = book(as("laura"), "wed", "s08-d-duna"); var rock = book(as("laura"), "mon", "s08-d-rock"); var toby = book(as("joan"), "thu", "s08-d-toby");
        clock.setInstant(local("2026-10-07T18:00")); // inside the threshold of Wednesday: the system is still never late
        int count;
        try (var t = TenantContext.open(CLUB)) { count = cancellations.cancelFutureByMember("s08-m-laura", BookingActor.system(), BookingCancelReason.LEAVE); }
        assertThat(count).isEqualTo(2);
        assertThat(booking(wed.path("id").asText())).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "LEAVE").containsEntry("late", false);
        assertThat(booking(rock.path("id").asText()).getString("packRefundMovementId")).isNotNull();
        assertThat(booking(toby.path("id").asText()).getString("state")).as("another owner's dog").isEqualTo("ACTIVE");
        assertThat(eventsOf("BookingCancelled")).extracting(e -> e.get("payload", Document.class).getString("by")).containsOnly("SYSTEM");
        try (var t = TenantContext.open(CLUB)) {
            var system = cancellations.cancelBySystem(toby.path("id").asText(), BookingCancelReason.INACTIVITY);
            assertThat(system.state()).isEqualTo(BookingState.CANCELLED); assertThat(system.late()).isFalse();
            assertThat(catchThrowableOfType(ApiException.class, () -> cancellations.cancelBySystem(toby.path("id").asText(), BookingCancelReason.INACTIVITY)).code())
                    .isEqualTo(ErrorCode.BOOKING_NOT_CANCELLABLE);
            assertThat(cancellations.cancelFutureByMember("s08-m-laura", BookingActor.system(), BookingCancelReason.LEAVE)).isZero();
        }
        dispatch();
        assertThat(count("notifications", Criteria.where("code").in("N-05", "N-36", "N-40"))).as("S13/S15 notify SYSTEM cancellations themselves").isZero();
    }

    @Test void T_08_46_levelChangesKeepBookingsAndClassTimeChangesRefreshTheDenormalisedCopies() throws Exception {
        var booking = book(as("laura"), "wed", "s08-d-duna"); String id = booking.path("id").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-d-duna")), new Update().set("levelId", "s08-lv-D"), "dogs");
        publish(new com.agilityhub.core.support.TestEvent("DogLevelChanged", null, CLUB, "Dog", "s08-d-duna", NOW, Map.of("dogId", "s08-d-duna"), null, null, DomainEvent.Origin.BACKOFFICE));
        dispatch();
        assertThat(booking(id)).containsEntry("state", "ACTIVE");
        var moved = local("2026-10-11T21:00"); // Sunday after the opening: the booking week moves to W1
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-wed")), new Update().set("startsAt", Date.from(moved)).set("endsAt", Date.from(moved.plusSeconds(3600))), "class_sessions");
        publish(new com.agilityhub.core.support.TestEvent("ClassSessionUpdated", null, CLUB, "ClassSession", "s08-wed", NOW, Map.of("classId", "s08-wed", "diff", Map.of("startTime", Map.of())), null, null, DomainEvent.Origin.BACKOFFICE));
        dispatch(); dispatch();
        assertThat(booking(id).getDate("classStartsAt").toInstant()).isEqualTo(moved); assertThat(booking(id).getString("bookingWeekKey")).isEqualTo("2026-10-11");
        assertThat(booking(id)).containsEntry("state", "ACTIVE");
        // A redelivered update changes nothing; an unknown class and an inconsistent club cancellation are only logged.
        var version = booking(id).get("version");
        publish(new com.agilityhub.core.support.TestEvent("ClassSessionUpdated", null, CLUB, "ClassSession", "s08-wed", NOW, Map.of("classId", "s08-wed"), null, null, DomainEvent.Origin.BACKOFFICE));
        publish(new com.agilityhub.core.support.TestEvent("ClassSessionUpdated", null, CLUB, "ClassSession", "s08-gone", NOW, Map.of(), null, null, DomainEvent.Origin.BACKOFFICE));
        publish(new com.agilityhub.core.support.TestEvent("ClassCancelledByClub", null, CLUB, "ClassSession", "s08-wed", NOW, Map.of("classId", "s08-wed"), null, null, DomainEvent.Origin.BACKOFFICE));
        publish(new com.agilityhub.core.support.TestEvent("BookingBlockChanged", null, CLUB, "Member", "s08-m-laura", NOW, Map.of("memberId", "s08-m-laura", "active", true), null, null, DomainEvent.Origin.BACKOFFICE));
        dispatch();
        assertThat(booking(id).get("version")).isEqualTo(version); assertThat(booking(id)).containsEntry("state", "ACTIVE");
        assertThat(count("domain_events", Criteria.where("status").is("FAILED"))).isZero();
    }

    @Autowired com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess classAccess;
    /** E5-T09 (E4-T05 review #8): the S08 counter writer never raises `booked` above the capacity, and always lets it go down. */
    @Test void E5_T09_theCounterWriterRefusesABookedCountAboveTheCapacity() {
        session("tiny", "2026-10-08T19:00", 2, List.of("s08-lv-C"));
        var keep = com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess.LowAlert.KEEP;
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(tx.execute(status -> classAccess.counters("s08-tiny", 2, 1, keep)).booked()).isEqualTo(2);
            assertThatThrownBy(() -> tx.execute(status -> classAccess.counters("s08-tiny", 3, 1, keep)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CLASS_FULL));
            assertThat(mongo.findById("s08-tiny", Document.class, "class_sessions").get("counters", Document.class)).containsEntry("booked", 2);
            // A class already over its capacity (older data, a capacity lowered by hand) can still release seats.
            mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-tiny")), new Update().set("counters.booked", 4), "class_sessions");
            assertThat(tx.execute(status -> classAccess.counters("s08-tiny", 3, 0, keep)).booked()).isEqualTo(3);
            assertThatThrownBy(() -> tx.execute(status -> classAccess.counters("s08-tiny", 4, 0, keep)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CLASS_FULL));
        }
    }
}
